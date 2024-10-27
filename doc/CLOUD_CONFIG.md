## Cloud-Config

MicroStar has its own property change handling which is done differently from Spring Cloud Config.

There are a few issues with Spring Cloud Config that, for us, warrant an alternative:

- it has an external dependency (Git, database via JDBC or S3)
- it needs a hook on that external dependency to notify it needs to reload configuration
- it does not know which services are affected when configuration changes
- services need to pull the configuration from the configuration server which
  is a manual step (the /actuator/refresh of the service needs to be called
  by an administrator).
- @RefreshScope property classes on the services will be updated with the
  altered configuration while we want immutable data structures
  <br/>

So instead, the Settings Service:

- has no external dependencies (it currently stores on local disk)
- has an api for altering the configuration files (or files can be altered
  on the file system directly)
- knows when files have changed by using a watch service on the file system
- keeps history / audit-trail
- knows which services rely on what configuration by remembering what files
  were touched for each service instance that asked for configuration
- services affected by configuration changes will be notified and can
  request a configuration update then.
- services have DynamicProperties that will be replaced when new configuration
  arrives. Any configuration changes that affect @ConfigurationProperties
  will lead to a service restart. This way the implementer can choose what
  property-changes will require a restart and what property-changes can be
  dealt with while running. See DynamicPropertiesManager.
- The DynamicPropertiesManager also tells what keys in the configuration
  have changed.
- restarting a service is managed by the Dispatcher so that if there are
  multiple services they will do a staggered restart making sure of
  uninterrupted service handling.
- configuration files naming can incorporate service-name and service-groups
  as well. So a 'story-reflector' service can, on the settings service, have a
  reflector\[-profile].yml which can be overridden by a story-reflector\[-profile].yml.

### @RefreshScope overwrites existing properties
This poses risks like any mutable structures. This is why the software community as a whole is
moving toward immutable data structures. An example for such a risk is setting a host and a port.
After getting the host from the properties and before getting the port the properties may have
changed resulting in a wrong host/port combination. Another issue is that in multithreaded
situations changing the data in one thread doesn't guarantee it is changed in the other thread
as well.

Spring supports immutable properties via the @ConstructorBinding annotation, however that is
not compatible with @RefreshScope.

It is also unclear what properties have changed which is sometimes needed to know for specific
updates (e.g. a database URL change would need a new database connection). For some high-impact
settings changes the AppContext may want to restart.

### The config server does not automatically call related services for updated properties
When property files change (e.g. in git or any other repository configured) the config server
will do nothing. It will serve these files to the services once requested. This leaves the burden
of what services to update on the maintainers who need to call the right services on their
'refresh' endpoint which then leads them to request the new settings.

## How does MicroStar fix this.

### DynamicProperties
Property classes can be annotated with **@DynamicProperties("some.path")**. They should be
immutable.

A **DynamicPropertiesManager** exists where objects can ask their settings and deserialize to the type
specified. A listener can be added to a dotted-path in the settings. The **DynamicPropertiesRef**
type acts like (and holds) an AtomicReference to these settings which uses the DynamicPropertyManager and
updates itself when settings change. Code can then call its get() method which returns an immutable
settings instance. An onChange() handler can be added as well which will notify the caller that
its settings have changed and what keys have changed.

The DynamicPropertiesManager is fully compatible with Spring and embeds itself as a property
source. So values loaded via the DynamicPropertiesManager will be included also when using
class Spring value injection, although these values won't change if the DynamicPropertiesManager
receives new data (where you can use the DynamicPropertiesRef instead).

High-impact settings can be configured to trigger an AppContext restart when updated.

Jackson is used for creating objects from data. Together with Lombok (@Builder, @Default,
@Jacksonized) this leads to compact code.

### Settings service
The settings service keeps track of what files are touched when combining the settings result
and keeps that together with the instanceId of the calling service. This way it knows what
service to inform that settings have updated. The service then can request its new settings.

The settings service has a dashboard where settings can be changed online. It also listens
for filesystem changes to the settings files (updating history in the process).

<br/>

**See** <br/>
[DynamicProperties.java](/microstar-spring/microstar-spring-common/src/main/java/net/microstar/spring/settings/DynamicProperties.java) <br/>
[DynamicPropertiesManager.java](/microstar-spring/microstar-spring-common/src/main/java/net/microstar/spring/settings/DynamicPropertiesManager.java) <br/>
[DynamicPropertiesRef.java](/microstar-spring/microstar-spring-common/src/main/java/net/microstar/spring/settings/DynamicPropertiesRef.java) <br/>
[DynamicPropertyRef.java](/microstar-spring/microstar-spring-common/src/main/java/net/microstar/spring/settings/DynamicPropertyRef.java) <br/>

[LogProperties.java](/microstar-spring/microstar-spring-common/src/main/java/net/microstar/spring/logging/LoggingProperties.java) properties example
