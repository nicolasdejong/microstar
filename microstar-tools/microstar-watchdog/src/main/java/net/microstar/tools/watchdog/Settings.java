package net.microstar.tools.watchdog;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Singular;
import lombok.ToString;
import net.microstar.common.conversions.DurationString;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.Encryption;
import net.microstar.common.util.StringUtils;
import net.microstar.common.util.VersionComparator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.microstar.common.io.IOUtils.pathOf;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.StringUtils.getRegexGroup;
import static net.microstar.common.util.StringUtils.getRegexGroups;
import static net.microstar.common.util.StringUtils.replacePattern;

/** This class receives command-line arguments and puts them in fields for consumption by the application */
@SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
@Builder @ToString
public class Settings {
    private static final Pattern JARS_DIR_PATTERN = Pattern.compile("^jarsDirs?:(.*)$");
    private static final Pattern INTERVAL_PATTERN = Pattern.compile("^interval:(.*)$");
    private static final Pattern PORT_PATTERN     = Pattern.compile("^port:(.*)$");
    private static final Pattern VAR_PATTERN      = Pattern.compile("^var:([^=]+)=(.*)$");
    private static final Pattern PARAM_PATTERN    = Pattern.compile("^param:(.*)$");
    private static final Pattern JPARAM_PATTERN   = Pattern.compile("^jparam:(.*)$");
    private static final Pattern ENCRYPT_PATTERN  = Pattern.compile("^encrypt:(.*)$");
    private static final Pattern HELP_PATTERN     = Pattern.compile("^-?(h(elp)?|\\?)$");
    private static final String  ENC_PREFIX       = "{cipher}";
    private static final Pattern ENC_PATTERN      = Pattern.compile("[{]cipher[}](.*)$");
    private static final String ENC_PASSWORD = "WatchdogV%s"; // Make it a bit harder to guess that this is the password by looking into the .class
    private static final Encryption encryption = new Encryption(Encryption.Settings.builder().build());
    private static final Comparator<String> VERSION_COMPARATOR = Comparator.comparing(
        path -> path.replaceAll("^.*-([^-]+)\\.jar", "$1"), VersionComparator.OLDEST_TO_NEWEST);

    private static final String JAVA_HOME = System.getProperty("java.home");
    private static final Path TEMP_DIR = IOUtils.createTempDir("microstar");
    static final String JAVA_CMD = Optional.of(
            String.join(File.separator, JAVA_HOME, "bin", "java"))
        .map(java -> {
            if (new File(java).exists()) return java;
            if (new File(java + ".exe").exists()) return java + ".exe";
            //noinspection ReturnOfNull
            return null; // leads to Optional.empty()
        }).orElseThrow(() -> new IllegalStateException("Unable to find the java executable!"));


    @Singular public final List<String> jarsDirs;
    @Default  public final Duration interval = Duration.ofSeconds(5);
    @Default  public final int port = 8080;
    @Singular public final List<String> jarVars;
    @Singular public final List<String>  params;
    @Singular public final List<String> jParams;
    @Default  public final String encrypt = "";
    @Default  public final boolean showHelp = false;


    public static Settings of(String... args) {
        final SettingsBuilder builder = Settings.builder();
        Stream.of(args)
          .flatMap(Settings::expandArg)
          .forEach(arg -> {
            final boolean isSupportedArgument =
                getParam (arg, JARS_DIR_PATTERN, dirs -> builder.jarsDirs(Stream.of(dirs.split("[,;]")).filter(dir -> !dir.isBlank()).toList()))
             || getParam (arg, INTERVAL_PATTERN, iv -> builder.interval(DurationString.toDuration(iv)))
             || getParam (arg, PORT_PATTERN,     pp -> builder.port(Integer.parseInt(pp)))
             || getParams(arg, VAR_PATTERN,      keyVal -> builder.jarVar(keyVal.get(0) + "=" + obfuscate(keyVal.get(0), decrypt(keyVal.get(1)))))
             || getParam (arg, PARAM_PATTERN,    builder::param)
             || getParam (arg, JPARAM_PATTERN,   builder::jParam)
             || getParam (arg, ENCRYPT_PATTERN,  builder::encrypt)
             || getParam (arg, HELP_PATTERN,     h -> builder.showHelp(true))
                ;
            if(!isSupportedArgument) {
                Log.error("Unsupported argument: {}", arg);
                throw new ExitException(10);
            }
        });
        if(!builder.port$set) { // if port is not set, get it from given dispatcher.url, if any
            Optional.ofNullable((List<String>)builder.jarVars).orElseGet(Collections::emptyList).stream()
                .filter(jarVar -> jarVar.contains("dispatcher.url"))
                .map(jarVar -> getRegexGroup(jarVar, ":(\\d+)").orElse("80"))
                .findFirst()
                .ifPresent(port -> builder.port(Integer.parseInt(port)));
        }
        if(builder.jarsDirs == null) builder.jarsDirs = new ArrayList<>(List.of(".")); // default jarsDir is current dir
        final Settings cla = builder.build();
        if(cla.showHelp) {
            cla.printHelpAndExit(0);
        }
        if(!cla.encrypt.isEmpty()) {
            Log.dump( ENC_PREFIX + encryption.encrypt(cla.encrypt, ENC_PASSWORD));
            Watchdog.exit(0);
        }
        return cla.validate(); // validate *after* showHelp & encrypt
    }

    private static boolean getParam(String arg, Pattern pattern, Consumer<String> handler) {
        return getRegexGroup (arg, pattern)
            .map(Settings::decrypt)
            .map(vals -> { handler.accept(vals); return vals; })
            .isPresent();
    }
    private static boolean getParams(String arg, Pattern pattern, Consumer<List<String>> handler) {
        return Optional.of(getRegexGroups (arg, pattern))
            .filter(vals -> !vals.isEmpty())
            .map(vals -> { vals.replaceAll(Settings::decrypt); return vals; })
            .map(vals -> { handler.accept(vals); return vals; })
            .isPresent();
    }
    private static String decrypt(String in) {
        return replacePattern(in, ENC_PATTERN, encText -> encryption.decrypt(encText, ENC_PASSWORD));
    }
    private static Stream<String> expandArg(String fileArg) {
        if(fileArg.startsWith("@")) {
            final Path path = pathOf(fileArg.substring(1));
            if(!Files.exists(path)) {
                Log.error("Unable to read arguments from given file: {}", path);
                Watchdog.exit(10);
            }
            return noThrow(() -> Files.readString(path)).map(Settings::splitArgsString).orElseThrow();
        }
        return Stream.of(fileArg);
    }
    private static Stream<String> splitArgsString(String argsString) {
        final Map<String,String> quoted = new HashMap<>();
        final String[] parts = StringUtils.replaceRegex(argsString, "\"([^\"]+)\"", groups -> {
            final String key = String.valueOf(quoted.size());
            quoted.put(key, groups[1]);
            return "\"->" + key + "<-\"";
        }).split("\\s+");
        return Arrays.stream(parts)
            .map(part -> StringUtils.replaceRegex(part, "\"->(\\d+)<-\"", groups -> quoted.get(groups[1])))
            ;
    }

    public List<String> getJavaCommand() { return getJavaCommand(Optional.empty()); }
    public List<String> getJavaCommand(Optional<String> dispatcherJarPath) {
        final String storePath = dispatcherJarPath.or(this::getDispatcherJarPath).orElse("unknown.jar");
        final Path jarPath = TEMP_DIR.resolve(storePath);
        if(!Files.exists(jarPath)) {
            Watchdog.jarsStore.read(storePath).ifPresent(bytes -> IOUtils.write(jarPath, bytes));
            //noThrow(() -> DataStoreUtils.copy(Watchdog.jarsStore.getStore(), storePath, jarPath).get());
        }
        return Stream.of(
            Stream.of(JAVA_CMD),
            jParams.stream(),
            jarVars.stream().map(kv -> "-D" + kv),
            Stream.of("-jar", jarPath.toAbsolutePath().toString()),
            params.stream()
        )
            .flatMap(Function.identity())
            .toList();
    }
    public Optional<String> getDispatcherJarPath() {
        if(jarsDirs.isEmpty()) return Optional.empty();
        final Path versionFile = Path.of("microstar-dispatcher-version-to-run"); // in case not the newest should be started
        final Optional<String> version = Files.exists(versionFile)
            ? noThrow(() -> Files.readString(versionFile))
            : Optional.empty();
        noThrow(() -> Files.deleteIfExists(versionFile));

        final List<String> dispatcherPaths = listAvailableDispatchers();

        if(version.isPresent()) {
            final String versionText = version.get().replace("/", "-").replaceFirst("^.*?(microstar)", "$1");
            Log.info("Found Dispatcher version file containing: {}", versionText);
            final Optional<String> toRun = dispatcherPaths.stream()
                .filter(path -> path.contains(versionText))
                .findFirst();
            if(toRun.isPresent()) return toRun; else Log.error("Requested jar nog found! -- starting newest instead");
        }
        return getLastOf(dispatcherPaths);
    }

    private List<String> listAvailableDispatchers() {
        return listFilesInDispatcherDirs()
            .filter(path -> path.matches("^(?is)(\\w+-)?dispatcher-.*?\\.jar$"))
            .sorted(VERSION_COMPARATOR) // oldest to newest
            .toList();
    }
    private Stream<String> listFilesInDispatcherDirs() {
        return Watchdog.jarsStore.list().stream().map(item -> item.path);
    }
    private Settings validate() {
        return this;
    }
    private Map<String,String> toMap() {
        return Map.of(
            "version", VersionChecker.CURRENT_VERSION,
            "jarsDirs", String.join(",", jarsDirs).replace("\\","/"),
            "jar", getDispatcherJarPath().map(p->p.replace("\\","/").replace("/./","/")).orElse("<not-found>"),
            "interval", DurationString.toString(interval),
            "port", "" + port
            // The below are not included in case some values are encrypted
            // which shouldn't be easily made plain-text by adding them in
            // the help template
            //"vars", String.join(",", jarVars),
            //"params", String.join(",",params),
            //"jParams", String.join(",", jParams)
        );
    }

    @SuppressWarnings("unused")
    private void failWithHelpIf(boolean condition, String message) { // NOSONAR -- for now unused since verify() is empty
        if(condition) {
            Log.error("ERROR: " + message); // NOSONAR -- no logging
            printHelpAndExit(10);
        }
    }
    private void printHelpAndExit(int exitCode) {
        final Map<String,String> values = toMap();
        final String helpTemplate = IOUtils.getResourceAsString("help.txt").orElse("No help found");
        final String helpText = StringUtils.replaceRegex(helpTemplate, "\\$\\{([^:}]+)(?::([^}]+))?\\} ?", groups ->
            Optional.ofNullable(values.get(groups[1])).filter(str -> !str.isEmpty())
                .orElse(groups[2] == null ? "" : (groups[2] + " "))
        );
        //noinspection UseOfSystemOutOrSystemErr
        System.out.println(helpText); // NOSONAR -- Don't log help text to logfile (as would happen with Log.dump())
        Watchdog.exit(exitCode);
    }

    private static <T> Optional<T> getLastOf(List<T> list) {
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size()-1));
    }
    private static String obfuscate(String key, String s) {
        return key.matches("(?i)^.*(secret|password).*$") && false // TODO: switch on when not just the last Dispatcher handles obfuscated system properties
            ? StringUtils.obfuscate(s)
            : s;
    }
}