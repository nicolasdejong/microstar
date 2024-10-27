package net.microstar.spring.application;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.conversions.DurationString;
import net.microstar.common.io.DeletedFileNotifier;
import net.microstar.common.model.ServiceId;
import net.microstar.common.model.ServiceRegistrationResponse;
import net.microstar.common.util.Reflection;
import net.microstar.common.util.StringUtils;
import net.microstar.common.util.ThreadBuilder;
import net.microstar.common.util.Threads;
import net.microstar.common.util.UserTokenBase;
import net.microstar.common.util.Utils;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.logging.LogFiles;
import net.microstar.spring.logging.MicroStarLogAppender;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;

/** Use like RestartableApplication -- All microservices should call start in this class */
@Slf4j
@Service
public abstract class MicroStarApplication extends RestartableApplication {
    // Unique instance id. Assumes there will be no collisions. Stays the same after restart (hence static).
    protected static final UUID staticServiceInstanceId =
        Optional.ofNullable(System.getProperties().get("instanceId")).map(Object::toString).map(UUID::fromString).orElseGet(UUID::randomUUID);
    private enum EventType { REGISTERED, UNREGISTERED, BEFORE_RESTART }
    public final long startTime = System.currentTimeMillis();
    public final AtomicLong activeTime = new AtomicLong(0); // Time at which the service is able to handle requests -- set by setIsActive()
    public final ServiceId serviceId;
    public final UUID serviceInstanceId; // copy of staticServiceInstanceId, for now. Having this as an instance field keeps the option open to at some point change implementation.
    private final AtomicReference<ServiceRegistrationResponse> serviceRegistrationResponse = new AtomicReference<>();
    private final Map<EventType, List<Runnable>> listeners = new EnumMap<>(EventType.class);
    private boolean isRegistered;
    private long unregisteredSince = System.currentTimeMillis();
    private static final Duration MAX_UNREGISTERED_TIME_DEFAULT = Duration.ofMinutes(10);

    static {
        MicroStarLogAppender.init(); // This will be overwritten once Spring Boot starts, so need to init again then
        initializers.add(MicroStarLogAppender::init); // Every time Spring Boot restarts it will stop all running appenders
    }

    /** Some things to do before the (Spring-)application starts */
    @SuppressWarnings("MethodOverridesStaticMethodOfSuperclass") // Some things to do before actually starting
    public static void start(Class<?> applicationClass, String... cmdLineArgs){
        try {
            // This statement has the side effect that it removes the cluster secret from
            // system properties (which prevents accidentally logging it).
            if("0".equals(MicroStarConstants.CLUSTER_SECRET)) log.info("No cluster secret");

            handleCompactedSettingsArgs();

            final ServiceId serviceId = ServiceId.of(applicationClass).setAsDefault();
            log.info("instanceId: " + staticServiceInstanceId);
            log.info("serviceId: " + serviceId);

            System.setProperty("spring.application.name", serviceId.combined);
            AppSettings.loadInitialSettingsFor(serviceId, staticServiceInstanceId, cmdLineArgs);
            LogFiles.getInstance().start(serviceId, staticServiceInstanceId); // needs just loaded settings
            RestartableApplication.start(applicationClass, cmdLineArgs);
        } catch(final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace(); // NOSONAR -- throw may happen before logging is initialized

            //noinspection CallToSystemExit
            System.exit(10); // Otherwise an exit(0) will follow while something bad happened
        }
    }

    public static Optional<MicroStarApplication> get() {
        return instanceOpt
            .filter(in -> in instanceof MicroStarApplication)
            .map(in -> (MicroStarApplication)in);
    }

    @SuppressWarnings("this-escape")
    protected MicroStarApplication() {
        serviceId = ServiceId.get();
        serviceInstanceId = staticServiceInstanceId;
        exitIfDeleteFileIsDeleted();
    }

    public static Class<?> getApplicationClass() { return RestartableApplication.class; }

    public static String getProtocol() {
        final String url = DynamicPropertiesManager.getProperty("app.config.dispatcher.url").orElse("");
        return StringUtils.getRegexGroup(url, "^(\\w+):").orElse("http");
    }

    public void setHeaders(HttpHeaders httpHeaders) {
        httpHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        httpHeaders.set(MicroStarConstants.HEADER_X_SERVICE_ID, serviceId.combined);
        httpHeaders.set(MicroStarConstants.HEADER_X_SERVICE_UUID, serviceInstanceId.toString());
        httpHeaders.set(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET);
        httpHeaders.set(UserToken.HTTP_HEADER_NAME, UserTokenBase.builder()
            .name(serviceId.combined)
            .email(serviceInstanceId.toString())
            .build()
            .toTokenString(MicroStarConstants.CLUSTER_SECRET));
    }

    public boolean isRegistered() { return serviceRegistrationResponse.get() != null; }

    public void setIsActive() {
        activeTime.set(System.currentTimeMillis());
        log.info("Dispatcher is now able to handle requests");
    }

    public Duration getStartupTime() {
        return activeTime.get() == 0 ? Duration.ZERO : Duration.ofMillis(activeTime.get() - startTime);
    }

    public Duration getRunTime() {
        return Duration.ofMillis(System.currentTimeMillis() - startTime);
    }

    public void onRegistered  (Runnable toCall) { addListenerFor(EventType.REGISTERED, toCall); }
    public void onUnregistered(Runnable toCall) { addListenerFor(EventType.UNREGISTERED, toCall); }
    public void onBeforeRestart(Runnable toCall) { addListenerFor(EventType.BEFORE_RESTART, toCall); }
    private void addListenerFor(EventType type, Runnable toCall) {
        synchronized (listeners) { listeners.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(toCall); }
    }
    private void removeSpringListeners() {
        synchronized (listeners) {
            for (final EventType type : EventType.values()) {
                listeners.computeIfPresent(type, (key, list) ->
                    new CopyOnWriteArrayList<>(list.stream().filter(not(MicroStarApplication::objOrParentHasSpringAnnotation)).toList())
                );
            }
        }
    }
    private void callListenersFor(EventType type) {
        final @Nullable List<Runnable> typeListeners;
        synchronized (listeners) { typeListeners = listeners.get(type); }
        if(typeListeners != null) typeListeners.forEach(Runnable::run);
    }
    private void callOnRegistered() { callListenersFor(EventType.REGISTERED); }
    private void callOnUnregistered() { callListenersFor(EventType.UNREGISTERED); }
    private void callOnBeforeRestart() { callListenersFor(EventType.BEFORE_RESTART); }

    /** Tests if the given object or any of its parent enclosures has a spring annotation */
    public static boolean objOrParentHasSpringAnnotation(Object obj) {
        return Reflection.objOrParentIsAnnotatedWith(obj, Component.class, Service.class, Bean.class);
    }

    @SuppressWarnings("this-escape")
    protected void exitIfDeleteFileIsDeleted() {
        final Path deleteFile = Path.of("DeleteToStopMicroStar");
        DeletedFileNotifier.forFile(deleteFile, "Running MicroStar services will stop when this file is deleted, in case this is not possible through the dashboard.", () -> {
            log.info("Stopping because the {} file was deleted", deleteFile.getFileName());
            stop();
        });
    }

    @Override protected void started() {
        super.started();
        register();
    }

    @Override protected void stopped() {
        super.stopped();
        if(isRegistered()) {
            getDispatcherDelegate().connectionChecker.stop();

            // This method is called from Spring Context Listener which may be running in reactor
            // thread which does not allow a block() call. Therefore, start the unregister action in its own thread.
            noThrow(() -> new ThreadBuilder().name("Unregister").run(() ->
                getDispatcherDelegate().unregister(this)
            ).join()); // normally join here would be bad (because blocking in Reactor thread) but we are stopping the server here
        }
    }

    @Override protected void aboutToRestart() {
        super.aboutToRestart();
        callOnBeforeRestart();
        removeSpringListeners(); // these components face end-of-life when restarting Spring so remove the references
        getDispatcherDelegate().aboutToRestart(this);
    }

    private void register() {
        Threads.execute(() -> {
            if(isRegistered) return;
            final DispatcherDelegate dispatcher = getDispatcherDelegate();
            exitWhenUnregisteredForTooLong();

            // dispatcher.register() may block for a long time or come back immediately if failed.
            // This depends on the implementation of register (e.g. mvc vs webflux).
            noThrow(() -> dispatcher.register(this))
                .ifPresentOrElse(response -> {
                    serviceRegistrationResponse.set(response);

                    log.info("Registered with Dispatcher");
                    setIsRegistered(true);
                    callOnRegistered();

                    dispatcher.whenDisconnected(this::onDispatcherDisconnect);
                }, () -> {
                    sleep(Duration.ofSeconds(7));
                    register();
                });
        });
    }

    private void setIsRegistered(boolean set) {
        if(isRegistered == set) return;
        isRegistered = set;
        unregisteredSince = set ? -1 : System.currentTimeMillis();
    }
    private Duration getUnregisteredDuration() {
        return unregisteredSince < 0 ? Duration.ZERO : Duration.ofMillis(System.currentTimeMillis() - unregisteredSince);
    }

    private void onDispatcherDisconnect() {
        setIsRegistered(false);
        callOnUnregistered();
        log.warn("Lost connection with dispatcher -- attempting to re-establish");
        getDispatcherDelegate().connectionChecker.stop();
        register();
    }

    private static DispatcherDelegate getDispatcherDelegate() {
        return instanceOpt.flatMap(app -> app.appContextOpt).orElseThrow().getBean(DispatcherDelegate.class);
    }

    private void exitWhenUnregisteredForTooLong() {
        new ThreadBuilder(() -> {
            int count = 0;
            while(!isRegistered) {
                final Duration maxUnregisteredDuration = DynamicPropertiesManager.getProperty("microstar.maxUnregisteredTime", Duration.class, MAX_UNREGISTERED_TIME_DEFAULT);
                if(count++%12 == 0) log.info("unregisteredTime: " + DurationString.toString(getUnregisteredDuration()) + " maxUnregisteredTime: " + DurationString.toString(maxUnregisteredDuration));
                if (Utils.is(getUnregisteredDuration()).greaterThan(maxUnregisteredDuration)) {
                    log.warn("Not connected to Dispatcher for more than max allowed time ({}) -- exit", DurationString.toString(maxUnregisteredDuration));
                    log.info("This max time can be set in microstar.maxUnregisteredTime to e.g. 2m or 2h, etc.");
                    stop();
                }
                sleep(Duration.ofSeconds(5));
            }
        }).isDaemon(true)
          .start();
    }

    /** Settings can be provided in a compacted command-line option: -Dsome.path=@;key=val;key=val;key=val */
    private static void handleCompactedSettingsArgs() {
        final Set<String> props = new HashSet<>();
        System.getProperties().forEach((keyObj, valObj) -> {
            final String key = String.valueOf(keyObj);
            final String val = String.valueOf(valObj);
            if(key.contains(".") && val.startsWith("@;")) {
                final Map<String,String> map = Arrays.stream(val.substring(2).split(";"))
                    .map(t->t.split("=",2)).filter(t->t.length == 2)
                    .collect(Collectors.toMap(t->t[0], t->t[1]));
                DynamicPropertiesManager.setProperty(key, map);
                props.add(key);
            }
        });
        props.forEach(System::clearProperty);
    }
}