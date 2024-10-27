package net.microstar.testing;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ShouldFlakyTestsBeIncludedCondition implements ExecutionCondition {
    private static final String ENV_NAME = "includeFlakyTests";

    @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return System.getProperty(ENV_NAME) == null && System.getenv(ENV_NAME) == null
            ? ConditionEvaluationResult.disabled("Skipping flaky test " + context.getDisplayName()
                + context.getElement()
                    .map(c -> c.getAnnotation(FlakyTest.class))
                    .map(FlakyTest::value)
                    .filter(reason -> !reason.isEmpty())
                    .map(reason -> " because: " + reason)
                    .orElse("")
                )
            : ConditionEvaluationResult.enabled("")
            ;
    }
}
