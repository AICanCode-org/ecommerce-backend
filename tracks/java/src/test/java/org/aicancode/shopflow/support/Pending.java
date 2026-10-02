package org.aicancode.shopflow.support;

import java.util.Map;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * PENDING test classes describe behaviour you have not built yet, so they are skipped
 * (Maven reports them as "Skipped"). When you start a milestone, delete its line from
 * {@link #PENDING}, watch the tests fail (red), implement until they pass (green).
 * Registered for every test automatically (see src/test/resources/junit-platform.properties).
 */
public class Pending implements ExecutionCondition {

    /** Test class simple name -> milestone. */
    static final Map<String, String> PENDING = Map.ofEntries(
            Map.entry("ValidationTest", "Phase 3 M1 - validation"),
            Map.entry("MoneyTest", "Phase 3 M1 - money and pricing"),
            Map.entry("SecurityTest", "Phase 3 M2 - passwords, tokens, webhook signatures"),
            Map.entry("AuthServiceTest", "Phase 3 M2 - AuthService"),
            Map.entry("ApiAuthTest", "Phase 3 M2 - auth endpoints"),
            Map.entry("CatalogServiceTest", "Phase 3 M3 - CatalogService"),
            Map.entry("ApiProductsTest", "Phase 3 M3 - product endpoints"),
            Map.entry("DescriptionTest", "Phase 3 M3 - AI descriptions"),
            Map.entry("SearchAssistTest", "Phase 3 M3 - AI search assist"),
            Map.entry("ApiAiTest", "Phase 3 M3 - AI endpoints"),
            Map.entry("CartServiceTest", "Phase 3 M4 - CartService and coupons"),
            Map.entry("ApiCartTest", "Phase 3 M4 - cart endpoints"),
            Map.entry("OrderServiceTest", "Phase 3 M5 - checkout and orders"),
            Map.entry("PaymentServiceTest", "Phase 3 M5 - payment webhook"),
            Map.entry("ApiOrdersTest", "Phase 3 M5 - order, webhook and outbox endpoints"),
            Map.entry("ApiIntegrationTest", "Phase 4 - integration tests"));

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return context.getTestClass()
                .map(c -> PENDING.get(c.getSimpleName()))
                .map(reason -> ConditionEvaluationResult.disabled("PENDING (" + reason + ") - see support/Pending.java"))
                .orElse(ConditionEvaluationResult.enabled("not pending"));
    }
}
