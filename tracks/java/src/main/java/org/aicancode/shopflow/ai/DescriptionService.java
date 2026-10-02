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
        // TODO(phase-3 M3): LLM if configured; ANY exception -> template with fallback=true (log a warning).
        //   No LLM -> template, fallback=false.
        throw new UnsupportedOperationException("TODO(phase-3 M3): DescriptionService.draft");
    }
}
