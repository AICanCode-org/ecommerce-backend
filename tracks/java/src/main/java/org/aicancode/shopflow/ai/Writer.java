package org.aicancode.shopflow.ai;

import java.util.List;
import org.aicancode.shopflow.domain.Models.Product;

/** Writes a product description draft. Implementations may throw: the service falls back. */
@FunctionalInterface
public interface Writer {

    String write(Product product, List<String> keywords) throws Exception;
}
