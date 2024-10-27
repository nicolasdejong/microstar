# FAQ

There may be code or design decisions that make you think: why? <br/>
This document attempts to answer these questions.

Note that we try to be open to any arguments that invalidate the given answer which
may lead to a different solution in the future.

Q: **Why is "independence" so important for you?** </br>
A: The author worked in environments where there was responsibility to keep a
   server running while not having access to that server (that had to be done via
   another party). This slowed new deployments and made research into issues
   harder. This feeling of not being in control made it so that the author sought
   for a result where most or all of the system could be influenced.

Q: **Why not use a service mesh?** </br>
A: A service mesh would be ideal but is often not available or overkill. Therefore,
   instead a simple and short Dispatcher was created that ticks all the boxes for
   being in control and staying as uncomplicated as possible. 

Q: **What goals existed that required building this Dispatcher?**<br/>
A: Independence, a simple and light local development solution (often limited
   hardware is available) and a solution where services can be run from multiple branches
   at the same time, mixed with services from the main branch. All from a single
   directory. This greatly simplifies deployments.

Q: **Why have a tailored Dispatcher when you can also use Spring Cloud Gateway?**<br/>
A: The Spring Cloud Gateway has extensive configuration so can do a lot of
   what the Dispatcher is doing. However, all these settings need to be set
   manually. This makes working with it much more cumbersome and static.
   An alternative would be to automatically generate these settings.
   That would probably lead to more complicated and harder to understand
   code than the current Dispatcher. The Dispatcher is less flexible but works
   with convention over configuration so always has the same behaviour.

Q: **Why not use a discovery system like Eureka?**<br/>
   MicroStar has a simple star configuration so all services just connect to the
   Dispatcher, which is the center of the star. Communication to other services
   should go via the Dispatcher as the service group determines what other
   service to call, keeps track of the load (e.g. for load-balancing) and makes
   sure callers don't get a 404 when a microservice is (re)starting (it holds
   the request until either the service is available again or a timeout occurs).
   A star formation also simplifies a traffic recorder for debugging purposes
   without needing to instrument all services.
   In a situation where there are many more services who all communicate with
   each other, Eureka would have more benefits (as would a service mesh).
   In the future something like Eureka could be added when the services
   are more distributed or a port is not easily configured. For now expected
   usage is all services in one machine / container.

Q: **Why check liveness by calling /version instead of /health endpoints?**<br/>
A: Mostly because it is independent of Spring. It just tests if the VM is still
   responding. But using another way to determine liveness should become configurable
   in the future so e.g. the Spring health endpoint can be used.

Q: **Why not let each service check for newer versions of itself and start that, just like the Watchdog?**<br/>
A: The watchdog always wants the last version running. This is not necessarily the case
   for services. In a production environment it should be possible to send partial traffic
   to the new service before entirely switching. Big bang service change has risks.

Q: **Why have an own Settings service when you can also use Spring Cloud Configuration server?**<br/>
A: See the [CLOUD_CONFIG](CLOUD_CONFIG.md) document that describes this in detail, but in short
   Spring Cloud Config does not work with immutable data structures and requires
   manual calls to the refresh endpoint of the various services when configuration
   has changed. The MicroStar solution works with immutable data structures and
   tells services when configuration has changed that affects them so they can ask
   for updated configuration automatically.

Q: **What files are loaded by the settings service?**<br/>
A:
  - services.yml
  - services-{profile}.yml (multiple times when multi-profile)
  - {service}.yml
  - {service}-{version}.yml
  - {service}-{profile}.yml (multiple times when multi-profile)
  - {serviceGroup}-{service}.yml
  - {serviceGroup}-{service}-{profile}.yml (multiple times when multi-profile)

  * Each file overrides previously loaded settings
  * Remove configurations that have a "spring.config.activate.on-profile" that not includes given profiles
  * Include configurations that are in the "spring.config.import", recursively
  * Both .yml and .yaml are supported
  
  The settings service will provide the combined settings from the above
  files which will be included in the Spring Boot configuration (it is
  inserted just after the system properties, meaning that system
  properties and up override these settings. Also see 'Externalized
  Configuration' in the Spring documentation.)

Q: **What settings are available for microstar?**<br/>
A: There is a separate document for [all microstar settings](../microstar-spring/global-configuration-doc.yaml).

Q: **How does authentication work here?**<br/>
A: The user logs in by calling "/login/username" (initial that is all
   that is needed) which ends up at the Authentication service which
   returns a token which the client should send from then on with
   every request in the "X-AUTH-TOKEN" http header (or equally named
   cookie, if that is enabled). Perhaps later activation can be done
   by sending a mail with a link (making sure the email address is
   correct and preventing logging in with a username of someone else).

Q: **Why not use a JWT token for authentication?**<br/>
A: The author apparently doesn't understand JWT enough because
   libraries full of functionality exist for creating, handling and validating
   JWT tokens, while all we need is a piece of data containing user info
   (like name, roles, expiry-time, etc) and salted encryption which can all
   be done from the Java VM libraries. No need for fancy pantsy libraries.

Q: **Why are there sometimes no empty lines between methods?**<br/>
A: This will become much clearer if you collapse the methods (shift+ctrl+-
   on Windows, shift+command+- on Mac).
