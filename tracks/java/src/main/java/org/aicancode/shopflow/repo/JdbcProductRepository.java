package org.aicancode.shopflow.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;
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
        // TODO(phase-3 M3): helper: map a products row to a Product.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.product");
    }

    @Override
    public Product insertProduct(Product p) {
        // TODO(phase-3 M3): INSERT ... RETURNING; unique violation on sku -> DuplicateError.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.insertProduct");
    }

    @Override
    public Optional<Product> findProduct(String sku) {
        // TODO(phase-3 M3): SELECT by sku (active or not: the service decides who may see it).
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.findProduct");
    }

    @Override
    public Optional<Product> updateProduct(Product p, int expectedVersion) {
        // TODO(phase-3 M3): UPDATE ... SET ..., version = version + 1 WHERE id = ? AND version = ? RETURNING;
        //   0 rows -> null (conflict).
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.updateProduct");
    }

    @Override
    public Optional<Product> adjustStock(long productId, int delta, Instant at) {
        // TODO(phase-3 M3): ONE statement: UPDATE products SET stock = stock + delta WHERE id = ? AND stock +
        //   delta >= 0 RETURNING; 0 rows -> null. Never read-then-write (ADR-0002).
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.adjustStock");
    }

    @Override
    public List<Product> searchProducts(ProductQuery q) {
        // TODO(phase-3 M3): active products WHERE id > after_id [AND search_vector @@
        //   plainto_tsquery('english', q)] [AND category] [AND price range] [AND stock > 0 / stock = 0 for
        //   inStock true/false] ORDER BY id LIMIT n.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.searchProducts");
    }

    @Override
    public List<CategoryCount> categories() {
        // TODO(phase-3 M3): SELECT category, COUNT(*) FROM products WHERE active GROUP BY category ORDER BY
        //   category.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcProductRepository.categories");
    }
}
