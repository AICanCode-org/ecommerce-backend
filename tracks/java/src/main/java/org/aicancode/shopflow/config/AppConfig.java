package org.aicancode.shopflow.config;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.aicancode.shopflow.ai.CircuitBreaker;
import org.aicancode.shopflow.ai.DescriptionService;
import org.aicancode.shopflow.ai.HttpLlmClient;
import org.aicancode.shopflow.ai.LlmClient;
import org.aicancode.shopflow.ai.LlmParser;
import org.aicancode.shopflow.ai.LlmWriter;
import org.aicancode.shopflow.ai.RuleParser;
import org.aicancode.shopflow.ai.SearchAssistService;
import org.aicancode.shopflow.ai.TemplateWriter;
import org.aicancode.shopflow.db.DatabaseUrl;
import org.aicancode.shopflow.db.Migrator;
import org.aicancode.shopflow.metrics.Metrics;
import org.aicancode.shopflow.repo.CartRepository;
import org.aicancode.shopflow.repo.JdbcCartRepository;
import org.aicancode.shopflow.repo.JdbcOrderRepository;
import org.aicancode.shopflow.repo.JdbcProductRepository;
import org.aicancode.shopflow.repo.JdbcUserRepository;
import org.aicancode.shopflow.repo.OrderRepository;
import org.aicancode.shopflow.repo.ProductRepository;
import org.aicancode.shopflow.repo.UserRepository;
import org.aicancode.shopflow.security.PasswordHasher;
import org.aicancode.shopflow.security.TokenService;
import org.aicancode.shopflow.security.Webhooks;
import org.aicancode.shopflow.service.AuthService;
import org.aicancode.shopflow.service.CartService;
import org.aicancode.shopflow.service.CatalogService;
import org.aicancode.shopflow.service.LogPublisher;
import org.aicancode.shopflow.service.OrderService;
import org.aicancode.shopflow.service.OutboxService;
import org.aicancode.shopflow.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires real adapters. Unit tests build the same services by hand with in-memory adapters. */
@Configuration
public class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public DataSource dataSource(AppProperties props) {
        DatabaseUrl url = DatabaseUrl.parse(props.databaseUrl());
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url.jdbcUrl());
        ds.setUsername(url.username());
        ds.setPassword(url.password());
        ds.setMaximumPoolSize(10);
        ds.setConnectionTimeout(5000); // when the database is down a request fails after 5 s, not 30 s
        return ds;
    }

    @Bean
    public Migrator migrator(DataSource dataSource, AppProperties props) {
        Migrator migrator = new Migrator(dataSource, Path.of(props.migrationsDir()));
        migrator.migrate(); // fail fast: the app must not serve traffic against an old schema
        return migrator;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public UserRepository userRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcUserRepository(JdbcClient.create(dataSource));
    }

    @Bean
    public ProductRepository productRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcProductRepository(JdbcClient.create(dataSource));
    }

    @Bean
    public CartRepository cartRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcCartRepository(JdbcClient.create(dataSource));
    }

    @Bean
    public OrderRepository orderRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcOrderRepository(JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Bean
    public AuthService authService(UserRepository users, AppProperties props, Clock clock) {
        TokenService tokens = new TokenService(TokenService.resolveSecret(props.jwtSecret()), clock);
        AuthService auth = new AuthService(users, new PasswordHasher(props.bcryptCost()), tokens, clock);
        if (!props.adminEmail().isBlank() && !props.adminPassword().isBlank()) {
            auth.ensureAdmin(props.adminEmail(), props.adminPassword());
            LOG.info("admin account ensured for {}", props.adminEmail());
        }
        return auth;
    }

    @Bean
    public Metrics metrics() {
        return new Metrics();
    }

    /** One LLM client behind one circuit breaker, shared by both AI features (v1.1). Null = offline. */
    @Bean
    public LlmClientHolder llmClient(AppProperties props, Metrics metrics) {
        if (!props.llmEnabled()) {
            return new LlmClientHolder(null);
        }
        HttpLlmClient http = new HttpLlmClient(props.llmBaseUrl(), props.llmModel(), props.llmApiKey(), props.llmTimeoutMs());
        return new LlmClientHolder(new CircuitBreaker(http, () -> metrics.inc(Metrics.LLM_SHORT_CIRCUITS)));
    }

    /** Wrapper so "no LLM configured" can be a bean too. */
    public record LlmClientHolder(LlmClient client) {
    }

    @Bean
    public DescriptionService descriptionService(LlmClientHolder llm) {
        return new DescriptionService(new TemplateWriter(), llm.client() == null ? null : new LlmWriter(llm.client()));
    }

    @Bean
    public SearchAssistService searchAssistService(LlmClientHolder llm) {
        return new SearchAssistService(new RuleParser(), llm.client() == null ? null : new LlmParser(llm.client()));
    }

    @Bean
    public CatalogService catalogService(ProductRepository products, Clock clock) {
        return new CatalogService(products, clock);
    }

    @Bean
    public CartService cartService(CartRepository carts, ProductRepository products, Clock clock) {
        return new CartService(carts, products, clock);
    }

    @Bean
    public OrderService orderService(OrderRepository orders, CartRepository carts, Clock clock) {
        return new OrderService(orders, carts, clock, Webhooks::newPaymentId);
    }

    @Bean
    public PaymentService paymentService(OrderRepository orders, AppProperties props, Clock clock) {
        return new PaymentService(orders,
                TokenService.resolveSecret(props.paymentWebhookSecret(), "PAYMENT_WEBHOOK_SECRET"), clock);
    }

    @Bean
    public OutboxService outboxService(OrderRepository orders, Clock clock) {
        return new OutboxService(orders, new LogPublisher(), clock);
    }
}
