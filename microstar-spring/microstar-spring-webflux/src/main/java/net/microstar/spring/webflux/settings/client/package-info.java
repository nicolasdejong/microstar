/** Settings client is here instead of in standalone settings.client package because:<p>
  *
  * - The Settings client is needed here while the client needs this as a dependency,
  *   which leads to a cycle.
  * - All services need the SettingsService. Having it in the microstar-spring-webflux
  *   library prevents the need for a dependency and an addition in the package scan.
  */
@NonNullByDefault
package net.microstar.spring.webflux.settings.client;

import net.microstar.common.NonNullByDefault;
