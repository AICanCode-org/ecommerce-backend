package org.aicancode.shopflow.ai;

import java.util.List;
import org.aicancode.shopflow.domain.Models.SearchFilters;

/** Turns a shopper's sentence into catalog filters. Implementations may throw: the service falls back. */
@FunctionalInterface
public interface Parser {

    SearchFilters parse(String query, List<String> categories) throws Exception;
}
