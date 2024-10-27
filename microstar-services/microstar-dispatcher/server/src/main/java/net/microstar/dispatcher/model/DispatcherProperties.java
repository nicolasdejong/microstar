package net.microstar.dispatcher.model;


import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.util.ByteSize;
import net.microstar.spring.settings.DynamicProperties;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.ADDED;
import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.DELETED;
import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.RUNNING;

@DynamicProperties("app.config.dispatcher")
@Builder @Jacksonized @ToString
@SuppressWarnings("FieldMayBeStatic")
public class DispatcherProperties {
    @Default public final String url = "http://localhost:8080";
    @Default public final Duration timeToWaitForStoppingDispatcher = Duration.ofSeconds(5);
    @Default public final ServicesProperties services = ServicesProperties.builder().build();
    @Default public final Map<String,String> mappings = Collections.emptyMap();
    @Default public final String fallback = "";
    @Default public final Set<String> websocketAccessRoles = Set.of("ADMIN");
    @Default public final JarsProperties jars = JarsProperties.builder().build();
    @Default public final StarsProperties stars = StarsProperties.builder().build();
    @Default public final BootstrapProperties bootstrap = BootstrapProperties.builder().build();
    @Default public final List<ResponseAction> responseActions = Collections.emptyList();
    @Default public final Set<String> retractedTokens = Collections.emptySet();
    @Default public final boolean allowGuests = false;
    @Default public final Set<String> allowGuestServices = Collections.emptySet();
    @Default public final Set<String> denyGuestServices = Collections.emptySet();
    @Default public final List<RestartRule> restartRules = Collections.emptyList();

    @Builder @Jacksonized @ToString
    public static class ServicesProperties {
        // Duration the Dispatcher should be active before services should be started.
        // This to prevent starting a service before any running service had
        // time to register itself to the Dispatcher.
        // (run time starts at application start, active time starts when the
        // service is ready to handle requests).
        // Services retry to register every 15s (old) or 7s (new).
        @Default public final Duration initialRegisterTime = Duration.ofSeconds(20);

        // Time a starting service is allowed to run until it should register
        // itself.
        @Default public final Duration startupTimeout     = Duration.ofSeconds(20);

        // Time between pings to a service to check if the service is still alive
        @Default public final Duration aliveCheckInterval = Duration.ofSeconds(10);

        // Time not called when a service should be stopped (not yet implemented)
        @Default public final Duration idleStopTime       = Duration.ofHours(48);

        // Time not called when a service should be removed (not yet implemented)
        @Default public final Duration idleRemoveTime     = Duration.ofDays(30);

        // True when a not-running service should be started when it is called
        // False to return a 404 when a not-running service is called
        @Default public final boolean  startWhenCalled    = true;
    }

    @Builder @Jacksonized @ToString
    public static class JarsProperties {
        // DataStores that will be searched for jar files (non-recursive!)
        @Default public final List<String> stores = List.of("jars");

        // Periodic check in case a datastore event is missed or a datastore target
        // was changed by an external action.
        @Default public final Duration pollPeriod = Duration.ofSeconds(30);
    }

    @Builder @Jacksonized @ToString
    public static class StarsProperties {
        // Time between pings to a service to check if the star is still alive
        @Default public final Duration aliveCheckInterval = Duration.ofSeconds(20);

        // Minimum time between notifications that stars have changed (alive check is async)
        @Default public final Duration notifyOfChangesDebounce = Duration.ofSeconds(5);

        // Properties of individual stars
        @Default public final List<StarProperties> instances = Collections.emptyList();

        public enum JarSyncType {
            ADDED,    // jars that are added on any star will be distributed to the local star
            DELETED,  // jars that are deleted on any star will be deleted from the local star as well
            RUNNING,  // jars that are running on any  star will be copied to the local star
            ALL       // all jars on all stars will be copied to the local star
        }
        @Default public final Set<JarSyncType> syncJars = EnumSet.of(ADDED, DELETED, RUNNING);

        // Service name (key) to star name (value) for calls to always end up at
        // that star, or set to "first-available-star" to always route requests
        // for the given service to the first available star in the cluster.
        @Default public final Map<String,String> serviceTargets = Collections.emptyMap();

        @Builder @Jacksonized @ToString
        public static class StarProperties {
            public final String url;
            public final String name;
        }
    }

    @Builder @Jacksonized @ToString
    public static class BootstrapProperties {
        // True when admin should be enabled in bootstrap mode
        @Default public final boolean adminEnabled = true;

        // Password to use for admin in bootstrap mode
        @Default public final String adminPassword = "admin";

        // Bootstrap mode ends if any of these services have registered
        @Default public final List<String> disableAdminWhenServices = List.of("microstar-authorization", "sso");
    }

    @Builder @Jacksonized @ToString
    public static class ResponseAction {
        @Default public final String service = "";
        @Default public final int status = 999;
        @Default public final String redirect = "";
    }

    @Builder @Jacksonized @ToString
    public static class RestartRule {
        @Default public final List<String> include = Collections.emptyList();
        @Default public final List<String> exclude = Collections.emptyList();
        @Default public final ByteSize maxProcMem = ByteSize.ZERO;
        @Default public final ByteSize maxHeapUsed = ByteSize.ZERO;
        @Default public final ByteSize maxMinHeapUsed = ByteSize.ZERO;
        @Default public final Duration maxUptime = Duration.ZERO;
        @Default public final LocalTime time = LocalTime.MAX;
    }
}
