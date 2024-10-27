package net.microstar.testing;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ShouldSlowTestsBeSkippedCondition implements ExecutionCondition {
    private static final String ENV_NAME = "skipSlowTests";

    @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return System.getProperty(ENV_NAME) == null && System.getenv(ENV_NAME) == null
            ? ConditionEvaluationResult.enabled("")
            : ConditionEvaluationResult.disabled("Skipping slow test " + context.getDisplayName());
    }
}
