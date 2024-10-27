package net.microstar.common;

import net.microstar.common.util.StringUtils;

import java.util.UUID;

public final class MicroStarConstants {
    private MicroStarConstants() {/*singleton*/}

    public static final String HEADER_X_CLUSTER_SECRET = "x-cluster-uuid";
    public static final String HEADER_X_SERVICE_UUID = "x-service-uuid";
    public static final String HEADER_X_SERVICE_ID = "x-service-id";
    public static final String HEADER_X_FORWARDED = "x-service-forwarded";
    public static final String HEADER_X_STAR_NAME = "x-star-name";
    public static final String HEADER_X_STAR_GATEWAY = "x-star-gateway";
    public static final String HEADER_X_STAR_TARGET = "x-star-target";
    public static final String URL_DUMMY_PREVENT_MATCH = "@dummy@prevent@match@";

    public static final UUID UUID_ZERO = UUID.fromString("0-0-0-0-0");

    /**
     * The clusterSecret exists so that only those services that know the secret
     * can communicate en connect with the rest of the cluster. Those connecting
     * without the secret need authentication.
     * <p>
     * This secret is used when services register themselves with the Dispatcher
     * and when a service starts to get the settings (which happens before
     * registration).
     * <p>
     * Copied in here from the System properties. Then removed from properties
     * to prevent accidental exposure.
     */
    public static final String CLUSTER_SECRET = StringUtils.getObfuscatedSystemProperty("clusterSecret")
        .filter(cs -> { System.clearProperty("clusterSecret"); return true; })
        .orElse("0");
}
