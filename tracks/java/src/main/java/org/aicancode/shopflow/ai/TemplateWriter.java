package org.aicancode.shopflow.ai;

import java.util.List;
import org.aicancode.shopflow.domain.Models.Product;

/** Deterministic and offline. The wording is part of the spec (the tests check it exactly). */
public class TemplateWriter implements Writer {

    public static final int DESCRIPTION_MAX = 600;

    @Override
    public String write(Product product, List<String> keywords) {
        // TODO(phase-3 M3): deterministic, the wording is part of the spec: '<name> from our <category>
        //   range.' + first sentence of the description + ' Highlights: a, b.' (if keywords) + ' In stock and
        //   ready to ship.' or ' Currently out of stock.'; shorten to DESCRIPTION_MAX.
        throw new UnsupportedOperationException("TODO(phase-3 M3): TemplateWriter.write");
    }
}
