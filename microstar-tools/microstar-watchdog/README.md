Watchdog
========

Watchdog will periodically try to create a server socket on port {configured}.
When that fails it means there is no Dispatcher running so it will start one.
Without a Dispatcher the system is not reachable and requires local admin
maintenance which should be avoided.

The Watchdog is kept as simple as possible. So no webserver to query for internal
state. Just logging to file. The fewer dependencies the better. This way no watchdog
is needed for the Watchdog.

The Watchdog will run from watchdog.jar. If it finds a watchdog-version.jar where
version is higher than its own, it will replace watchdog.jar by the found higher
version and run from there. This way the name stays stable which is needed for
a cron job or scheduled task.

The Watchdog will stop if the logfile is written to by anybody but itself. This
way it detects and prevents multiple watchdogs running at the same time (started
from the same directory anyway).

# Later

- Periodically do a GET /ok to check for responsiveness.

To figure out:
- How to stop an unresponsive Dispatcher.
  When the Dispatcher is started from the Watchdog using a ProcessBuilder,
  a process.destroy/destroyForcibly() can be used. However when the Watchdog was restarted the
  ProcessBuilder instance will be lost or if the Dispatcher is started otherwise no pid is known.
  When no process-id (pid) is available the 'jps' command can be used (command line tool
  that should be provided with all JVMs) to get the pid. The 'kill <pid>' command (on windows it
  is 'taskkill /F /PID <pid>') can then be used to stop the non-responsive VM.
  Always notify an admin if this happens. It can hold info on if a restart was possible.
- Email address(es) of admin to notify of (re)start events (what email server to use then?)
- Signal or Telegram bot for the really important messages like 'the server is down and I can't restart!'.
