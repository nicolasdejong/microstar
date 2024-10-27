Settings Service
================

Manages a set of settings files. This is a (very simple) alternative to Spring Cloud
Config that integrates with the Dispatcher star setup. A history is kept per
resource for every change.

Two directories are used: 'current/' containing all settings files and
'history/{fileVersion}/' where the history for every file in current is kept.

When a settings file is updated here, it will be combined (see below). The list
of touched files will be remembered for the calling service instance so when
later a file is changed it is known what services will be affected.

The service will load the combined settings from the settings-server and
integrate them with a PropertySource into the Spring environment.

This service will be updated later to also support database storage.

## The combining algorithm

The calling service (next called {serviceGroup} and {service}) should send its
current profile (like dev or prod, or from spring.profiles.default), next called
{profile}. The calling service should include its instanceId as a header
preventing other clients from accessing its settings (and so the settings service
can remember what files that service instance needs).

In the next list a map is filled where each consecutive map will overwrite
the previous one for the keys it defines. They'll get skipped if non-existent.

- try to load {service}.yaml
- try to load {service}-{version}.yaml
- try to load {service}-{profile}.yaml (multiple times when multi-profile)
- try to load {serviceGroup}-{service}.yaml
- try to load {serviceGroup}-{service}_{profile}.yaml (multiple times when multi-profile)

## Include support when combining

**spring.config.import** for adding other files *after* the document that holds the import(s).</br>
**spring.config.activate.on-profile** only use the keys in these settings if given profile(s) are active.

If an import is needed that *prepends* the file (like configuration that contains default
values), do the import at the beginning of the file and follow it with a --- line.
(A --- line splits a file into multiple documents. The imported settings will be included
between the two then)

**Note**: Any other Spring configuration keys are **not** supported here.
          The idea is that this is a simple environment so no time needs to
          be spent copying Spring features that won't be used.
          Also, @variables@ are not available since this code is running
          outside the Spring app that will load this configuration.

## Endpoints

---
GET /file/{fileVersion}   Get a specific file<br/>
GET /combined/{profile}   Get a combined properties file (who it is for is in the http-headers)
---
The http-header should contain an instanceId UUID (x-service-uuid). The Dispatcher
will not allow access unless a valid instanceId is included. The instanceId is received
by a service when registering.

## Updating settings

The settings files can be edited in a text editor and be overwritten directly on the
file system. History files will be created automatically when a change is detected
(interpreted as changed by user 'filesystem'). However, it is better to use the dashboard
included with the dispatcher on {dispatcher-url}/dashboard which includes user authentication
and does not require local system access.
