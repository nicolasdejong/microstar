package net.microstar.tools.watchdog;

import net.microstar.common.io.IOUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PidFile {
    private PidFile() {}
    private static final String pid = String.valueOf(ProcessHandle.current().pid());
    private static final Path file = Path.of("watchdog.pid");

    static {
        try {
            Files.writeString(file, pid);
        } catch (final IOException e) {
            Log.error("Unable to create pid file -- exit");
            throw new ExitException(10);
        }
    }

    public static boolean isOurs() {
        try {
            if(!Files.exists(file)) return true;
            final String filePid = Files.readString(file);
            final boolean isOurPid = pid.equals(filePid);
            if(!isOurPid) Log.info("PID file changed to different pid: {}", filePid);
            return isOurPid;
        } catch (final IOException e) {
            Log.error("No PID file: {}", e.getMessage());
            throw new ExitException(10);
        }
    }
    public static void del() {
        if(isOurs()) IOUtils.del(file);
    }
}
