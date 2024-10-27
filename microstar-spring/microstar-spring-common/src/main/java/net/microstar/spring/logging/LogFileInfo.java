package net.microstar.spring.logging;

import lombok.Builder;
import net.microstar.common.model.ServiceId;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

@Builder(toBuilder = true)
class LogFileInfo {
    public final long timestamp;
    public final long timestampEnd;
    public final int part;
    public final String instanceId; // only partial
    public final String version;
    public final String group;

    public static LogFileInfo of(Path path) {
        return of(path.toFile().getName());
    }
    public static LogFileInfo of(ServiceId serviceId, UUID instanceId, int part) {
        return builder()
            .timestamp(System.currentTimeMillis())
            .part(part)
            .instanceId(instanceId.toString())
            .version(serviceId.version)
            .group(serviceId.group)
            .build();
    }
    public static LogFileInfo of(String filename) {
        final String[] partsIn = filename.replaceAll("\\.log$", "").split("[_]");
        final String[] parts = new String[6];
        Arrays.fill(parts, "");
        System.arraycopy(partsIn, 0, parts, 0, partsIn.length);
        return builder()
            .timestamp(LogFilesUtil.getEpochSecondOfString(parts[0]))
            .group(parts[1])
            .version(parts[2])
            .instanceId(parts[3])
            .part(LogFilesUtil.parseInt(parts[4]))
            .timestampEnd(LogFilesUtil.getEpochSecondOfString(parts[5]))
            .build();
    }

    public LogFileInfo end() {
        return toBuilder().timestampEnd(System.currentTimeMillis()).build();
    }

    public String toFilename() {
        return String.join("_",
            LogFilesUtil.getDateTimeString(timestamp),
            group,
            version,
            instanceId.replaceAll("^(\\w+)-(\\w+)-(\\w+)-(\\w+)-(\\w+)$", "$1$2"),
            String.valueOf(part),
            LogFilesUtil.getDateTimeString(timestampEnd)
        ) + ".log";
    }
}
