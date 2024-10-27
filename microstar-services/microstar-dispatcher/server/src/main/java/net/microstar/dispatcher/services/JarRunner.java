package net.microstar.dispatcher.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.webflux.settings.client.SettingsService;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static java.lang.ProcessBuilder.Redirect.INHERIT;
import static net.microstar.common.MicroStarConstants.UUID_ZERO;
import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
@Component
@RequiredArgsConstructor
public class JarRunner {
    private static final String JAVA_HOME = System.getProperty("java.home");
    private static final String JAVA_CMD = Optional.of(
        String.join(File.separator, JAVA_HOME, "bin", "java"))
        .map(java -> {
            if (new File(java).exists()) return java;
            if (new File(java + ".exe").exists()) return java + ".exe";
            //noinspection ReturnOfNull
            return null; // leads to Optional.empty()
        }).orElseThrow(() -> new FatalException("Unable to find the java executable"));
    public static final String VM_ARGS_KEY = "vmArgs";
    public static final List<String> JAR_CONFIG_FILENAMES = List.of(
        "vmargs",
        ".vmargs",
        "BOOT-INF/classes/vmargs",
        "BOOT-INF/classes/.vmargs",
        "BOOT-INF/classes/application.yml",
        "BOOT-INF/classes/application.yaml",
        "BOOT-INF/classes/application.properties"
    );


    private final SettingsService settingsService;

    public void run(ServiceId serviceId, JarInfo jarInfo, Map<String, String> variables) {
        Threads.execute(() -> {
            @Nullable Path jarsDir = null;
            try {
                // Cannot run jar from DataStore directly, so first copy onto the local filesystem (deleted in finally)
                jarsDir = Files.createTempDirectory("JarRunner_Temp");
                final Path jarPath = jarsDir.resolve(jarInfo.name);
                DataStoreUtils.copy(jarInfo.store.get(), jarInfo.name, jarPath).get();

                final List<String> arguments = collectCommandLineArguments(serviceId, jarPath.toFile(), variables);
                final ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.directory(new File(".").getAbsoluteFile()); // run not from the temp dir but from the dir the Dispatcher is running from
                processBuilder.command(arguments);
                processBuilder.redirectOutput(INHERIT); // For some reason DISCARD sometimes blocks, causing hang.
                processBuilder.redirectError(INHERIT);  // INHERIT does not combine logs so fine for now.

                final Process process = run(processBuilder, serviceId, jarPath.toFile(), arguments);
                process.waitFor();
            } catch(final RuntimeException | InterruptedException | ExecutionException | IOException runEx) { // NOSONAR -- catch all
                log.error("Error running {}: {}", jarInfo.name, runEx.getMessage(), runEx);
            } finally {
                // Delete the jar that was copied from the DataStore containing the original jar
                if(jarsDir != null) IOUtils.delTree(jarsDir);
            }
        });
    }

    protected Process run(ProcessBuilder processBuilder, ServiceId serviceId, File jarFile, List<String> arguments) {
        log.info("Starting jar {}: {}", jarFile.getName(), arguments);
        try {
            return processBuilder.start();
        } catch (final IOException cause) {
            log.error("Unable to start service ({}) jar ({}) with args ({}): {}", serviceId, jarFile, arguments, cause.getMessage());
            throw new IllegalStateException("Unable to start jar for " + serviceId, cause);
        }
    }

    private List<String> collectCommandLineArguments(ServiceId serviceId, File jarFile, Map<String, String> variables) {
        final List<String> arguments = new ArrayList<>();
        arguments.add(JAVA_CMD);
        arguments.addAll(variables.entrySet().stream()
            .filter(entry -> Optional.ofNullable(entry.getValue()).filter(s -> !s.isEmpty()).isPresent())
            .map(entry -> String.format("-D%s=%s", entry.getKey(), entry.getValue()))
            .toList());
        arguments.addAll(getConfiguredVmArgs(serviceId, jarFile));
        arguments.add("-jar");
        arguments.add(jarFile.getAbsolutePath());
        log.info("CmdLineArguments: {}", arguments);
        return arguments;
    }

    /** Get 'vmArgs' from settings or, if not found there, try to read from application.yaml in jar */
    private List<String> getConfiguredVmArgs(ServiceId serviceId, File jarFile) {
        final @Nullable Map<String,Object> cfgVars = settingsService
            .getSettings(List.of("default"), UUID_ZERO, serviceId)
            .onErrorResume(e-> {
                log.warn("Unable to get configuration for starting service {}", serviceId);
                return Mono.just(Collections.emptyMap());
            })
            .block();
        @Nullable Object vmArgs = cfgVars == null || cfgVars.get(VM_ARGS_KEY) == null ? loadVmArgsFromJar(jarFile) : cfgVars.get(VM_ARGS_KEY);

        //    vmArgs: -some -args -here
        // or vmArgs: ['-some','-args','-here']
        if(vmArgs instanceof Map<?,?> map) vmArgs = map.get(VM_ARGS_KEY); // next statements will increase specificity
        if(vmArgs instanceof List<?> list) return list.stream().filter(String.class::isInstance).map(String.class::cast).filter(s -> !s.isEmpty()).toList();
        if(vmArgs instanceof String[] s) return Arrays.asList(s);
        if(vmArgs instanceof String s) return Arrays.asList(s.split("\\s+"));
        return List.of();
    }
    private @Nullable Object loadVmArgsFromJar(File jarFile) {
        String type = "";
        String cfgText = "";
        try (final FileSystem fileSystem = FileSystems.newFileSystem(jarFile.toPath(), (ClassLoader) null)) {
            for(final String filename : JAR_CONFIG_FILENAMES) {
                final Path fileToExtract = fileSystem.getPath(filename);
                type = filename.contains(".") ? filename.replaceAll("^.*\\.([^.]+)$", "$1") : "";
                cfgText = noThrow(() -> Files.readString(fileToExtract)).orElse("");
                if (!cfgText.isEmpty()) break;
            }
        } catch (final IOException e) {
            log.warn("Unable to read configuration from {}", jarFile, e);
        }
        return convertConfigText(type, cfgText);
    }
    private @Nullable Object convertConfigText(String type, String cfgText) {
        if(cfgText.isEmpty()) return Collections.emptyList();
        @Nullable Object vmArgs = null;
        try {
            vmArgs = switch(type) {
                case "properties" -> loadPropertiesFrom(cfgText);
                case "yaml", "yml" -> new Yaml().load(cfgText);
                case "", "vmargs" -> cfgText;
                default -> {
                    log.warn("Encountered unexpected configuration type: {}", type);
                    yield "";
                }
            };
        } catch(final Exception e) {
            log.warn("Unable to parse jar configuration data of type {}: {} -- input: {}", type.isEmpty() ? "<none>" : type, e.getMessage(), cfgText);
        }
        return vmArgs;
    }
    private Properties loadPropertiesFrom(String text) {
        final Properties props = new Properties();
        noThrow(() -> props.load(new StringReader(text)));
        return props;
    }
}