package net.microstar.statics.model;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

@Jacksonized
@Builder
@ToString
public class OverviewItem {
    public final String path;
    public final long length;
    public final long crc;
    public final long lastModified;
}
