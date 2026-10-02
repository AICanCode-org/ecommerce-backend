package org.aicancode.shopflow.ai;

import java.util.List;
import org.aicancode.shopflow.domain.Models.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Product description drafts for admins: TemplateWriter (offline) or LlmWriter with a fallback. A draft is never
 * saved automatically: an admin reads it and saves it with PATCH (human in the loop).
 */
public class DescriptionService {

    private static final Logger LOG = LoggerFactory.getLogger(DescriptionService.class);
    private final TemplateWriter template;
    private final Writer llm;

    public DescriptionService(TemplateWriter template, Writer llm) {
        this.template = template;
        this.llm = llm;
    }

    /** provider: template | llm. */
    public record Draft(String provider, boolean fallback, String description) {
    }

    public Draft draft(Product product, List<String> keywords) {
        if (llm != null) {
            try {
                return new Draft("llm", false, llm.write(product, keywords));
            } catch (Exception e) { // any LLM failure -> graceful fallback
                LOG.warn("LLM description failed, using the template: {}", e.toString());
                return new Draft("template", true, template.write(product, keywords));
            }
        }
        return new Draft("template", false, template.write(product, keywords));
    }
}
