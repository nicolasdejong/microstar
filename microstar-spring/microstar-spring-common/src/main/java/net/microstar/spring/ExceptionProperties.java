package net.microstar.spring;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.spring.settings.DynamicProperties;

import java.util.Set;

@DynamicProperties("microstar.exceptions")
@Builder @Jacksonized @ToString
public class ExceptionProperties {
    @Default public final Set<Integer> errorStatusNotToLog = Set.of(401, 403, 404);
    @Default public final Set<Integer> errorStatusNotToLogStackTrace = Set.of(404);
    @Default public final Set<Integer> errorStatusToSendLogStackTrace = Set.of(500);
    @Default public final boolean      errorStatusTruncateStackTrace = true;
}
