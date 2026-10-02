package org.aicancode.shopflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration from environment variables (12-factor). Same names in every track. */
@Component
public class AppProperties {

    private final String databaseUrl;
    private final String migrationsDir;
    private final String jwtSecret;
    private final int bcryptCost;
    private final String adminEmail;
    private final String adminPassword;
    private final String paymentWebhookSecret;
    private final String aiProvider;
    private final String llmBaseUrl;
    private final String llmApiKey;
    private final String llmModel;
    private final int llmTimeoutMs;

    public AppProperties(
            @Value("${DATABASE_URL:postgresql://shopflow:shopflow@localhost:5432/shopflow}") String databaseUrl,
            @Value("${MIGRATIONS_DIR:../../spec/db/migrations}") String migrationsDir,
            @Value("${JWT_SECRET:}") String jwtSecret,
            @Value("${BCRYPT_COST:10}") int bcryptCost,
            @Value("${ADMIN_EMAIL:}") String adminEmail,
            @Value("${ADMIN_PASSWORD:}") String adminPassword,
            @Value("${PAYMENT_WEBHOOK_SECRET:}") String paymentWebhookSecret,
            @Value("${AI_PROVIDER:offline}") String aiProvider,
            @Value("${LLM_BASE_URL:http://localhost:11434/v1}") String llmBaseUrl,
            @Value("${LLM_API_KEY:}") String llmApiKey,
            @Value("${LLM_MODEL:llama3.2:1b}") String llmModel,
            @Value("${LLM_TIMEOUT_MS:5000}") int llmTimeoutMs) {
        this.databaseUrl = databaseUrl;
        this.migrationsDir = migrationsDir;
        this.jwtSecret = jwtSecret;
        this.bcryptCost = bcryptCost;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.paymentWebhookSecret = paymentWebhookSecret;
        this.aiProvider = aiProvider.toLowerCase(java.util.Locale.ROOT);
        this.llmBaseUrl = llmBaseUrl.replaceAll("/+$", "");
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.llmTimeoutMs = llmTimeoutMs;
    }

    public String databaseUrl() {
        return databaseUrl;
    }

    public String migrationsDir() {
        return migrationsDir;
    }

    /** Empty means "not set": a random per-process secret is used (dev only). */
    public String jwtSecret() {
        return jwtSecret;
    }

    public int bcryptCost() {
        return bcryptCost;
    }

    public String adminEmail() {
        return adminEmail;
    }

    public String adminPassword() {
        return adminPassword;
    }

    /** Empty means "not set": a random per-process secret is used (dev only). */
    public String paymentWebhookSecret() {
        return paymentWebhookSecret;
    }

    public boolean llmEnabled() {
        return "llm".equals(aiProvider);
    }

    public String llmBaseUrl() {
        return llmBaseUrl;
    }

    public String llmApiKey() {
        return llmApiKey;
    }

    public String llmModel() {
        return llmModel;
    }

    public int llmTimeoutMs() {
        return llmTimeoutMs;
    }
}
