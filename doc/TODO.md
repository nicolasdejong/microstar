# TODO

# ISSUES
- sso settings via DynamicPropertiesManager fail (try with local sso.yaml file)
- rename settings files not working?
- limit logging for pentest tools (incl some very large stack traces)
- properties override not working always correctly? (no reproduction yet) -- waiting for a specific use-case
- service crash during starting -- how does Dispatcher react? Does it notice?


# Later
- local configuration files should reload when changed (including when imported yaml files change)
- local configuration files should be editable via dashboard
- Exceptions should support log like {} groups
- A service should be able to say to the Dispatcher: only one instance allowed
- filter for NotFoundException to remove the first path part (/public/)
- settings-files for main/non-main groups (like in a situation where we have prod & test on the same machine)
- callback for when service has started (e.g. to determine if more instances of itself are running)

# Dashboard
- add filter in dashboard/services/[dormant-]services
- Frontend should be aggregate of frontends of connected services instead of the all-in-one it is now (how?)
  - iframe seems to be the only way?
  - is duplication of network.ts the only way?
- Option to 'restart' service
- dispatcher traffic percentages

# Database
- https://auth0.com/blog/integrating-spring-data-jpa-postgresql-liquibase/
- run locally using H2, on servers use given PostgreSQL -- run in 'dev' profile

# Architecture improvements
- Storage class should replace any Path, File & Files usage to abstract away file access (so e.g. impl via JDBC)
- Add Clock util class that replaces everything related to time and date so tests can be faster (e.g. sleeps and waits) (HARD!)

# Dispatcher
- Dispatcher endpoint configuration:
  - Request mapping, Response mapping (e.g. adding a secret to a request, changing path, etc)
  - Mandatory roles per endpoint in configuration?
  - Default roles for new users in configuration?
- Support rename of jars (like renaming group name from 'dev' to 'accept')
- restarting of services should be managed by Dispatcher? E.g. with load balancing not both services should restart at the same time
- disable serviceIds for versions that don't behave well. This should lead to a fallback to a previous version, if available, or else 404

# Security:
- Use https between services so no eavesdropping is possible? (enable in cfg or always? or enabled by default?)
  https://stackoverflow.com/questions/4914033/java-secure-socket-without-authentication
  https://docs.oracle.com/cd/E19118-01/n1.sprovsys51/819-1655/faptz/index.html
- ? Add possibility to refresh cluster secret (requires re-encrypt for enc prop values)
- Add 're-encrypt' feature that allows for changing the encPassword and updates all {cipher} values in all settings

# Settings
- Configuration history max-age or max-count configuration to prevent thousands of history files
- Dashboard: see what files a service depends on ('Used by' 'select instance')

# Logging
- When Splunk: RESEARCH: does the MicroStar logging end up at Splunk? If not, how?
- Logger should filter on sensitive values (perhaps this is built into Spring already?)
- When no Splunk: Combining split logs
- When no Splunk: Log viewer should access historic logs
- When no Splunk: Log viewer should not load complete log (which may be huge) but have buttons on top for more history
- set log level depending on current user (probably not possible as a user has no dedicated thread but worth investigating)
  It is possible (but cumbersome) to set a context in the reactor chain.

# Various
- Facade for defaultEndpoints?
- Configuration readme that combines and documents all properties used by MicroStar
- File uploader to get jars on the server
- Tool: simple app to pipe Maven output into to filter logging (it is so much!) (typical friday afternoon project)
- More unit tests to increase the test coverage

# To think about:
- On production do we want immediate rollout of new versions, or manual traffic change from old to new?
- Periodic usage report from log by each service (Dispatcher should generate some basic usage values
  per service, like percentage of each return status, number of calls). Perhaps log these about once a
  minute and generate nice graphics from that log.
