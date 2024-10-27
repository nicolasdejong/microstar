package net.microstar.spring.application;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.DurationString;
import net.microstar.common.util.ThreadBuilder;
import net.microstar.spring.settings.DynamicPropertiesContextInitializer;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerInitializedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.microstar.common.util.Utils.sleep;

/**
 * Sometimes it is needed to restart a Spring Boot application, for example when there are new settings
 * (ConstructorBinding and RefreshScope don't work together: a restart fixes that).
 * Your application should extend this class to gain this functionality, like this:<pre>
 *
 * class YourApplication extends RestartableApplication {}
 *
 *      public static void main(String... args) {
 *          setArgs(args);
 *          new YourApplication().start(); // NOTE: start() or restart() will create a new instance of YourApplication but will be ok when injected as bean
 *      }
 *  </pre>
 */
@Slf4j
public class RestartableApplication {
    public static final Duration STOP_DELAY = Duration.ofMillis(100);
    private static String[] args = new String[0]; // as given in start, needed for restart
    static Class<?> appClass = Object.class; // as given in start, needed for restart
    protected static final List<ApplicationContextInitializer<ConfigurableApplicationContext>> initializers = new ArrayList<>();
    protected static Optional<RestartableApplication> instanceOpt = Optional.empty();
    protected Optional<ConfigurableApplicationContext> appContextOpt = Optional.empty();
    private static long lastRestartTime = 0;
    private int serverPort = 0;

    static {
        Runtime.getRuntime().addShutdownHook(new ThreadBuilder().toRun(() -> log.info("VM is shutting down")).build());
    }


    @SuppressWarnings("this-escape")
    public RestartableApplication() {
        instanceOpt = Optional.of(this); // NOSONAR -- needed for bootstrapping
    }

    public static Duration getLastStartAgo() {
        return Duration.ofMillis(System.currentTimeMillis() - lastRestartTime);
    }

    public static void start(Class<?> applicationClass, String... cmdLineArgs) {
        appClass = applicationClass;
        if(cmdLineArgs.length > 0) RestartableApplication.args = cmdLineArgs; // don't overwrite args if no args given (restart)
        instanceOpt = Optional.empty();

        try {
            final ConfigurableApplicationContext cac = new SpringApplicationBuilder(applicationClass)
                .initializers(new DynamicPropertiesContextInitializer())
                .initializers(initializers.toArray(ApplicationContextInitializer[]::new))
                .listeners(event -> instanceOpt.ifPresent(instance -> {
                    if (event instanceof ReactiveWebServerInitializedEvent webServerEvent)
                        instance.serverPort = webServerEvent.getWebServer().getPort();
                    if (event instanceof ContextClosedEvent) instance.stopped();
                }))
                .run(RestartableApplication.args);

            instanceOpt.ifPresent(instance -> {
                instance.appContextOpt = Optional.of(cac);
                instance.started();
            });
        } catch(final Throwable t) { // NOSONAR -- catch all for startup crash
            log.error("Startup error", t);
            log.error("Failed to start -- exit");
            System.exit(10);
        }
    }
    public void stop() {
        log.info("Stopping");

        // stop() may be called from a Reactor thread which will throw
        // if any block() calls are done. To prevent this, run it a
        // separate thread.
        new ThreadBuilder()
            .name("Stopping")
            .isDaemon(true)
            .initialDelay(STOP_DELAY)
            .run(() -> {
                appContextOpt.ifPresent(ctx -> SpringApplication.exit(ctx, () -> 0));
                //noinspection CallToSystemExit -- Actually stopping the VM here because nothing left to do (All @PreDestroy calls have been made already by now)
                System.exit(0);
            });
    }
    public void restart() {
        appContextOpt.ifPresent(appContext -> {
            log.info("Restarting...");
            lastRestartTime = System.currentTimeMillis(); // NOSONAR -- this value does not survive restart if not static
        });

        // from https://www.baeldung.com/java-restart-spring-boot-app
        new ThreadBuilder(() -> {
            sleep(Duration.ofMillis(150));
            aboutToRestart();
            appContextOpt.ifPresent(ConfigurableApplicationContext::close);
            RestartableApplication.start(appClass);
        })
            .isDaemon(false) // this line prevents the VM from exiting when the old appContext is closed
            .name("SpringRestarter")
            .start();

    }

    public int getServerPort() {
        return serverPort == 0
            ? Integer.parseInt(
                DynamicPropertiesManager.getProperty("local.server.port")
                    .or(() -> DynamicPropertiesManager.getProperty("server.port"))
                    .filter(port -> !port.isBlank())
                    .orElse("0"))
            : serverPort;
    }
    public static Optional<Integer> getAppServerPort() {
        return instanceOpt.map(RestartableApplication::getServerPort).filter(p -> p != 0);
    }
    public static ConfigurableApplicationContext getContext() {
        return instanceOpt.orElseThrow().appContextOpt.orElseThrow();
    }

    protected void aboutToRestart() {/*for overloading*/}
    protected void started() {/*for overloading*/ logRestartTime(); }
    protected void stopped() {/*for overloading*/ log.info("Application is shutting down"); }

    protected static void logRestartTime() {
        if(lastRestartTime != 0) log.info("Restarting took " + DurationString.toString(Duration.ofMillis(System.currentTimeMillis() - lastRestartTime)));
    }
}
