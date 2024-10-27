/**
 * Relay is used to perform requests from the service to another service via the Dispatcher.
 * These service(s) can be part of the current star (the Dispatcher + services the calling
 * service is part of) or other stars as defined in app.config.dispatcher.stars<p>
 *
 * Typically these relay requests are performed via an inbetween class so the calling
 * services don't have to use the Relay classes directly.<p>
 *
 * These classes are not dependent on webflux or webmvc so they can be used by both.
 */
@NonNullByDefault
package net.microstar.dispatcher.model;

import net.microstar.common.NonNullByDefault;
