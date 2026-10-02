package org.aicancode.shopflow.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;

/** Persistence port for the catalog. */
public interface ProductRepository {

    Product insertProduct(Product product);

    Optional<Product> findProduct(String sku);

    /** Optimistic locking: empty when the stored version is not expectedVersion (ADR-0003). */
    Optional<Product> updateProduct(Product product, int expectedVersion);

    /** One guarded statement: empty when the stock would go below 0 (ADR-0002). */
    Optional<Product> adjustStock(long productId, int delta, Instant at);

    List<Product> searchProducts(ProductQuery query);

    List<CategoryCount> categories();
}
