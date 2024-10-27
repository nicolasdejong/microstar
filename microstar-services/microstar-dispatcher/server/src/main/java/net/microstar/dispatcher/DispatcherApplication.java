package net.microstar.dispatcher;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.application.RestartableApplication;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.http.HttpStatus;

import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
public class DispatcherApplication extends MicroStarApplication {
    public static final int PORT_IF_NO_PORT_IN_URL = 80;
    private static final DynamicPropertiesRef<DispatcherProperties> props = DynamicPropertiesRef.of(DispatcherProperties.class);
    static {
        DynamicPropertiesManager.addPropertyThatRequiresRestart("app.config.dispatcher.url");
    }

    @SuppressWarnings("MethodOverridesStaticMethodOfSuperclass") // override needed to prevent registration in started/stopped
    public static void start(Class<?> applicationClass, String... cmdLineArgs) {
        final int serverPort = getConfiguredPort(cmdLineArgs);
        // initializers.add(applicationContext -> {}) // on restart

        stopOlderVersionOtherwiseStopThis(serverPort);

        setServerPortProperty(serverPort);
        MicroStarApplication.start(applicationClass, cmdLineArgs);
    }

    @Override protected void started() {/*don't run super -- talks with Dispatcher which is this*/
        logRestartTime();
    }
    @Override protected void stopped() {/*don't run super -- talks with Dispatcher which is this*/}
    @Override protected void aboutToRestart() {/*don't run super -- talks with Dispatcher which is this*/}

    private static int               getConfiguredPort(String... cmdLineArgs) {
        return AppSettings.getDispatcherUrl(cmdLineArgs).flatMap(DispatcherApplication::getPortFromUrl)
            .or(() -> Optional.of(props.get().url).flatMap(DispatcherApplication::getPortFromUrl))
            .or(DispatcherApplication::getServerPortFromInstance)
            .orElse(PORT_IF_NO_PORT_IN_URL);
    }
    private static Optional<Integer> getPortFromUrl(String url) {
        return noThrow(() -> URI.create(url).getPort());
    }
    private static Optional<Integer> getServerPortFromInstance() {
        return instanceOpt.map(RestartableApplication::getServerPort);
    }
    private static void setServerPortProperty(int port) {
        System.setProperty("server.port", String.valueOf(port));
    }

    private static void stopOlderVersionOtherwiseStopThis(int serverPort) {
        // If no other version was found (most use-cases) no other Dispatcher is running at this time
        getServiceIdOfRunningDispatcher(serverPort).ifPresent(
            otherVersion -> {
                final ServiceId myVersion = ServiceId.get();
                if(myVersion.isNewerThan(otherVersion)) {
                    log.info("This Dispatcher ({}) is newer than a running Dispatcher ({}) -- Stopping other Dispatcher", myVersion, otherVersion);
                    stopOtherDispatcherOrExitWhenThatFails(serverPort);
                    waitUntilPortIsAvailableOrExit(serverPort);
                } else {
                    log.info("This Dispatcher ({}) is not newer than a running Dispatcher ({}) -- Exit", myVersion, otherVersion);
                    stopVM(0);
                }
            }
        );
    }
    private static Optional<ServiceId> getServiceIdOfRunningDispatcher(int serverPort) {
        return noThrow(() -> {
            final URL url = URI.create("http://localhost:" + serverPort + "/version").toURL();
            final HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET);

            final int responseCode = con.getResponseCode();
            final String receivedText = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\"",""); // json string
            con.disconnect();

            return responseCode == HttpStatus.OK.value()
                ? ServiceId.of(receivedText)
                : null;
        });
    }
    private static void stopOtherDispatcherOrExitWhenThatFails(int serverPort) {
        final boolean stopping = noThrow(() -> {
            final URL url = URI.create("http://localhost:" + serverPort + "/stop").toURL();
            final HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET);

            try {
                final String receivedText = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\"", ""); // json string
                //noinspection UseOfSystemOutOrSystemErr -- this is before the Logger initialized
                if(!receivedText.trim().isEmpty()) log.info("Dispatcher to stop gives reply: " + receivedText);
            } catch(final Exception e) {
                //noinspection CallToPrintStackTrace -- this is before the Logger initialized
                e.printStackTrace();
            }

            con.disconnect();
            return con.getResponseCode() == HttpStatus.OK.value();
        }).orElse(false);
        if(!stopping) {
            log.error("Unable to stop other Dispatcher -- exit");
            stopVM(10);
        }
    }
    private static void waitUntilPortIsAvailableOrExit(int serverPort) {
        final long tMax = System.currentTimeMillis() + props.get().timeToWaitForStoppingDispatcher.toMillis();
        do {
            final Optional<ServerSocket> serverSocket = noThrow(() -> new ServerSocket(serverPort));
            if (serverSocket.isPresent()) {
                noThrow(() -> serverSocket.get().close());
                return; // success!
            }
            noThrow(() -> Thread.sleep(100));
        } while(System.currentTimeMillis() <= tMax);
        log.error("Other Dispatcher didn't stop (port not freed) -- exit");
        stopVM(10);
    }
    private static void stopVM(int exitCode) {
        //noinspection CallToSystemExit -- Server hasn't started yet so no problem to exit()
        System.exit(exitCode);
    }
}
