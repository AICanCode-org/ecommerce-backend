package org.aicancode.shopflow.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for the catalog. */
public class JdbcProductRepository implements ProductRepository {

    static final String COLS = "id, sku, name, description, category, price_paise, stock, active, version, created_at, "
            + "updated_at";
    private final JdbcClient jdbc;

    public JdbcProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Product product(ResultSet rs, int row) throws SQLException {
        return new Product(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("description"),
                rs.getString("category"), rs.getLong("price_paise"), rs.getInt("stock"), rs.getBoolean("active"),
                rs.getInt("version"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }

    @Override
    public Product insertProduct(Product p) {
        try {
            return jdbc.sql("INSERT INTO products (sku, name, description, category, price_paise, stock, active, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLS)
                    .params(p.sku(), p.name(), p.description(), p.category(), p.pricePaise(), p.stock(), p.active(),
                            Jdbc.ts(p.createdAt()), Jdbc.ts(p.updatedAt()))
                    .query(JdbcProductRepository::product).single();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("sku");
        }
    }

    @Override
    public Optional<Product> findProduct(String sku) {
        return jdbc.sql("SELECT " + COLS + " FROM products WHERE sku = ?").param(sku)
                .query(JdbcProductRepository::product).optional();
    }

    @Override
    public Optional<Product> updateProduct(Product p, int expectedVersion) {
        // Optimistic locking: the WHERE clause only matches if nobody saved in between (ADR-0003).
        return jdbc.sql("UPDATE products SET name = ?, description = ?, category = ?, price_paise = ?, active = ?, "
                        + "updated_at = ?, version = version + 1 WHERE id = ? AND version = ? RETURNING " + COLS)
                .params(p.name(), p.description(), p.category(), p.pricePaise(), p.active(), Jdbc.ts(p.updatedAt()),
                        p.id(), expectedVersion)
                .query(JdbcProductRepository::product).optional();
    }

    @Override
    public Optional<Product> adjustStock(long productId, int delta, Instant at) {
        // One statement, guarded: never read-modify-write stock in application code (ADR-0002).
        return jdbc.sql("UPDATE products SET stock = stock + ?, updated_at = ? WHERE id = ? AND stock + ? >= 0 "
                        + "RETURNING " + COLS)
                .params(delta, Jdbc.ts(at), productId, delta)
                .query(JdbcProductRepository::product).optional();
    }

    @Override
    public List<Product> searchProducts(ProductQuery q) {
        List<String> where = new ArrayList<>(List.of("active"));
        List<Object> params = new ArrayList<>();
        if (q.afterId() != null) {
            where.add("id > ?"); // keyset pagination: no OFFSET scan
            params.add(q.afterId());
        }
        if (q.q() != null && !q.q().isEmpty()) {
            where.add("search_vector @@ plainto_tsquery('english', ?)");
            params.add(q.q());
        }
        if (q.category() != null) {
            where.add("category = ?");
            params.add(q.category());
        }
        if (q.minPricePaise() != null) {
            where.add("price_paise >= ?");
            params.add(q.minPricePaise());
        }
        if (q.maxPricePaise() != null) {
            where.add("price_paise <= ?");
            params.add(q.maxPricePaise());
        }
        if (q.inStock() != null) {
            where.add(q.inStock() ? "stock > 0" : "stock = 0");
        }
        params.add(q.limit());
        return jdbc.sql("SELECT " + COLS + " FROM products WHERE " + String.join(" AND ", where) + " ORDER BY id LIMIT ?")
                .params(params).query(JdbcProductRepository::product).list();
    }

    @Override
    public List<CategoryCount> categories() {
        return jdbc.sql("SELECT category, COUNT(*) AS n FROM products WHERE active GROUP BY category ORDER BY category")
                .query((rs, row) -> new CategoryCount(rs.getString("category"), rs.getLong("n"))).list();
    }
}
