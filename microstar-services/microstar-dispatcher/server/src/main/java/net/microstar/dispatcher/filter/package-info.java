@NonNullByDefault
package net.microstar.dispatcher.filter;

import net.microstar.common.NonNullByDefault;

/*
  Web filters are called ordered. This is the current order:

  Request:
    HI - CounterFilter
    HI - UrlToHeadersWebFilter
    HI - RequestLogger
     3 - MappingsWebFilter
     5 - ServiceToStarMapperFilter
    10 - TokenValidatorWebFilter
    20 - SetServiceIdInRequestFilter
    40 - PreventLocalMatchForOtherStarWebFilter

  Response:
    LO - StarNameIntoResponseWebFilter
    LO - ResponseActionsFilter
 */