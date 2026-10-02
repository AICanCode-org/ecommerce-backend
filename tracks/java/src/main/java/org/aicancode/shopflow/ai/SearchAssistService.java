package org.aicancode.shopflow.ai;

import java.util.List;
import org.aicancode.shopflow.domain.Models.SearchFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Search assist for everyone: RuleParser (offline) or LlmParser with a fallback to the rules. */
public class SearchAssistService {

    private static final Logger LOG = LoggerFactory.getLogger(SearchAssistService.class);
    private final RuleParser rules;
    private final Parser llm;

    public SearchAssistService(RuleParser rules, Parser llm) {
        this.rules = rules;
        this.llm = llm;
    }

    /** provider: rules | llm. */
    public record Assist(String provider, boolean fallback, SearchFilters filters) {
    }

    public Assist interpret(String query, List<String> categories) {
        if (llm != null) {
            try {
                return new Assist("llm", false, llm.parse(query, categories));
            } catch (Exception e) { // any LLM failure -> graceful fallback
                LOG.warn("LLM search assist failed, using the rules: {}", e.toString());
                return new Assist("rules", true, rules.parse(query, categories));
            }
        }
        return new Assist("rules", false, rules.parse(query, categories));
    }
}
