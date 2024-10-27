package net.microstar.dispatcher.model;

import lombok.Builder;
import net.microstar.common.model.ServiceId;

import java.util.List;
import java.util.UUID;

@Builder
public class ServicesForClient {

    @Builder
    public static class Service {
        public final ServiceId id;
        public final String state;
        public final UUID instanceId;
        public final long runningSince;
        public final int requestCount24h;
        public final int requestCount8h;
        public final int requestCount10m;
        public final int requestCount1m;
        public final int trafficPercent;
    }

    public final List<Service> services;
}
