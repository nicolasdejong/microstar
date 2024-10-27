package net.microstar.common.model;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

import javax.annotation.Nullable;
import java.util.UUID;

/** This data is provided to the Dispatcher by the service that wishes to register */
@Jacksonized @ToString @Builder
public class ServiceRegistrationRequest {
    public final String id; // service id as string
    @Nullable public final UUID instanceId; // will be set to random UUID when not provided
    @Builder.Default @SuppressWarnings("FieldMayBeStatic") // false positive
    public final String protocol = "http"; // "http" or "https"
    public final int listenPort;
    @Nullable public final String url; // alternative for (overrides) protocol and listenPort
    public final long startTime;
}
