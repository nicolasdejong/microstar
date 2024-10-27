package net.microstar.common.model;


import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

import java.util.UUID;

@Jacksonized @Builder @ToString
public class ServiceRegistrationResponse {
    public final UUID serviceInstanceId;
    public final int isAlivePort;
}
