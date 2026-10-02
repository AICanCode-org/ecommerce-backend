package org.aicancode.shopflow.ai;

import java.util.List;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Text;

/** Deterministic and offline. The wording is part of the spec (the tests check it exactly). */
public class TemplateWriter implements Writer {

    public static final int DESCRIPTION_MAX = 600;

    @Override
    public String write(Product product, List<String> keywords) {
        StringBuilder text = new StringBuilder(product.name() + " from our " + product.category() + " range.");
        String sentence = Text.firstSentence(product.description());
        if (!sentence.isEmpty()) {
            text.append(' ').append(sentence);
        }
        if (!keywords.isEmpty()) {
            text.append(" Highlights: ").append(String.join(", ", keywords)).append('.');
        }
        text.append(product.stock() > 0 ? " In stock and ready to ship." : " Currently out of stock.");
        return Text.shorten(text.toString(), DESCRIPTION_MAX);
    }
}
