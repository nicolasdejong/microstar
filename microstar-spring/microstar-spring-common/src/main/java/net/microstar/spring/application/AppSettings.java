package net.microstar.spring.application;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.io.FileTreeChangeDetector;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ExceptionUtils;
import net.microstar.common.util.Threads;
import net.microstar.spring.logging.LogLevelHandler;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.settings.SpringProps;
import org.springframework.http.HttpStatus;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static net.microstar.common.io.FileTreeChangeDetector.ChangeType.MODIFIED;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ThreadUtils.debounce;

/**
 * Takes care of loading settings both *before* and after starting the Spring
 * application. It handles changes in settings that may lead to partial reloads
 * or restarting of the application.<p>
 *
 * Starting the Spring application will lead to the creation of a number of beans
 * and properties which all use loaded configuration. Therefore, initially, the
 * configuration has to be loaded *before* Spring starts. Alternatively the app
 * can start, then load settings followed by a restart but loading before is more elegant.
 */
@Slf4j
public final class AppSettings {
    private static final String LOCATION_KEY = "app.config.dispatcher.url";
    private static final String LOCATION_DEFAULT = "http://localhost:8080";
    private AppSettings() {/*util*/}

    public static void loadInitialSettingsFor(ServiceId serviceId, UUID serviceInstanceId, String[] startArgs) {
        loadLocalSettings(startArgs, serviceId);

        // This won't work for Dispatcher as the call is performed via the dispatcher
        if ("microstar-dispatcher".equals(serviceId.name)) return;

        // Call the settings-service to load configured settings
        Threads.execute(() ->
            loadFromSettingsService(serviceId, serviceInstanceId, startArgs)
            .ifPresent(AppSettings::handleExternalSettingsText)
        );
    }

    public static void handleExternalSettingsText(String receivedSettingsText) {
        if(receivedSettingsText.startsWith("<")) {
            log.warn("Received fallback html as settings!");
            return;
        }
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromYaml(receivedSettingsText));
        LogLevelHandler.update();
    }

    @Nullable
    private static FileTreeChangeDetector localFilesChangeDetector;

    private static void loadLocalSettings(String[] startArgs, ServiceId serviceId) {
        final List<String> profiles = SpringProps.getActiveProfileNames(startArgs);
        final Path localDir = Path.of(".").toAbsolutePath();

        doLoadLocalSettings(localDir, serviceId, profiles);

        if (localFilesChangeDetector == null) {
            localFilesChangeDetector = new FileTreeChangeDetector(localDir, (path,type) -> {
                if(type != MODIFIED) return;
                if(!path.getFileName().toString().matches("^(services|" + serviceId.name + ").*$")) return;
                debounce(Duration.ofSeconds(2), () -> doLoadLocalSettings(localDir, serviceId, profiles));
            }).watch();
        }
    }
    private static void doLoadLocalSettings(Path localDir, ServiceId serviceId, List<String> profiles) {
        // TODO does not support imports for now. Later generalize SettingsCollector (OrderedSettings) and both use the generic code
        final PropsMap localSettings = Stream.of(
                Stream.of(                       getResourceForName(localDir, "services")),
                Stream.of(                       getResourceForName(localDir, "config/services")),
                profiles.stream().map(profile -> getResourceForName(localDir, "services-" + profile)),
                profiles.stream().map(profile -> getResourceForName(localDir, "config/services-" + profile)),
                Stream.of(                       getResourceForName(localDir, serviceId.name)),
                Stream.of(                       getResourceForName(localDir, "config/" + serviceId.name)),
                profiles.stream().map(profile -> getResourceForName(localDir, serviceId.name + "-" + profile)),
                profiles.stream().map(profile -> getResourceForName(localDir, "config/" + serviceId.name + "-" + profile))
            )
            .flatMap(Function.identity())
            .flatMap(Optional::stream)
            .map(localSettingsText -> ExceptionUtils.noThrowMap(() -> PropsMap.fromYaml(localSettingsText), ex -> {
                log.error("Error reading local settings: {}", ex.getMessage());
                return PropsMap.empty();
            }))
            .reduce(PropsMap.empty(), (a, b) -> PropsMap.getWithOverrides(a, b));

        DynamicPropertiesManager.setLocalSettings(localSettings);
        LogLevelHandler.update();
    }

    private static Optional<String> getResourceForName(Path root, String name) {
        return Optional.of(name)
            .flatMap(s -> Stream.of("", ".yml", ".yaml", ".properties") // include empty in case ext is already given
                .map(ext -> s + ext)
                .flatMap(n -> noThrow(() -> Files.readString(root.resolve(n))).stream())
                .findFirst()
            );
    }

    private static boolean isSettingsService(ServiceId serviceId) {
        return "microstar-settings".equals(serviceId.name);
    }

    /** This is used before Spring has started. To load services after Spring has started, use SettingsService facade */
    private static Optional<String> loadFromSettingsService(ServiceId serviceId, UUID serviceInstanceId, String[] startArgs) {
        Optional<String> urlBase = Optional.empty();

        if(isSettingsService(serviceId)) { // The settings-service needs to call itself, so wait until it has started
            int retries = 20;
            while(urlBase.isEmpty() && retries-->0) {
                urlBase = RestartableApplication.getAppServerPort().map(port -> "http://localhost:" + port);
                if(urlBase.isEmpty()) noThrow(() -> Thread.sleep(250));
            }
        } else {
            urlBase = getDispatcherUrl(startArgs).map(dispatcherUrl -> IOUtils.concatPath(dispatcherUrl, "microstar-settings"));
        }
        try {
            final URL url = URI.create(IOUtils.concatPath(
                urlBase.orElse(LOCATION_DEFAULT),
                "combined",
                String.join(",", SpringProps.getActiveProfileNames(startArgs))
            )).toURL();
            final HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty(MicroStarConstants.HEADER_X_SERVICE_ID, serviceId.combined);
            con.setRequestProperty(MicroStarConstants.HEADER_X_SERVICE_UUID, serviceInstanceId.toString());
            con.setRequestProperty(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET);

            final int status = con.getResponseCode();
            final String receivedText = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            con.disconnect();
            if(status != HttpStatus.OK.value()) {
                final String error = new String(con.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Error response from " + url + ": " + status + "; error=" + error + " msg=" + receivedText);
            }
            if(receivedText.startsWith("<")) {
                log.error("Failed to load initial settings!! (received fallback html) url: {}", url);
                log.warn(receivedText);
                return Optional.empty();
            }
            return Optional.of(receivedText);
        } catch (final MalformedURLException cause) {
            log.error("Failed to load initial settings: malformed url: " + cause.getMessage());
        } catch (final FileNotFoundException cause /*thrown when a 404 is returned*/) {
            log.error("Failed to load initial settings: the settings service is not running");
        } catch (final IOException cause) {
            log.error("Failed to load initial settings: {}", cause.getMessage().contains("refused: connect") ? "no dispatcher" : cause.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Dispatcher location is in the Spring configuration but if Spring is not started yet that
     * information is not yet available. In that case this method attempts to make a good guess
     * what the location will be by loading configuration and scanning the start arguments.<p>
     *
     * The location of this setting is 'app.config.dispatcher.url', which can be found in the following
     * order, where each concurrent source overwrites the previous one:<pre>
     *
     * - application.y[a]ml
     * - application-[profile].yml
     * - system property
     * - command-line: --path.to.field=value
     *
     * </pre>
     * (lower overrides former)
     * Spring is more complicated than this, but we probably won't be using other ways.
     */
    public static Optional<String> getDispatcherUrl(String[] startArgs) {
        return getKeyValue(LOCATION_KEY, startArgs);
    }

    private static Optional<String> getKeyValue(String key, String[] startArgs) {
        return SpringProps.fromCommandLine(key, startArgs)
            .or(() -> SpringProps.fromSystemProperties(key))
            .or(() -> SpringProps.getActiveProfileNames(startArgs).stream()
                .flatMap(profile -> SpringProps.fromResource(key, "application-" + profile, "application").stream())
                .findFirst())
            ;
    }
}