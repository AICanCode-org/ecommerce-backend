package org.aicancode.shopflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.aicancode.shopflow.domain.Models.SearchFilters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** M6: search assist - deterministic offline rules, strict validation of LLM answers. */
class SearchAssistTest {

    static final List<String> CATS = List.of("bags", "kitchen", "shoes");

    private static SearchFilters parse(String q) {
        return new RuleParser().parse(q, CATS);
    }

    private static SearchFilters filters(List<String> terms, String category, Long min, Long max, Boolean inStock) {
        return new SearchFilters(terms, category, min, max, inStock);
    }

    /** Answers every chat with a fixed text (or throws) and records the prompts. */
    static final class FakeChat implements LlmClient {
        final List<String[]> calls = new ArrayList<>();
        private final String answer;

        FakeChat(String answer) {
            this.answer = answer;
        }

        @Override
        public String chat(String system, String user, int maxTokens) {
            calls.add(new String[] {system, user});
            if (answer == null) {
                throw new IllegalStateException("slow");
            }
            return answer;
        }
    }

    @Test
    void extractsPriceCategoryAndStock() {
        assertThat(parse("red running shoes under ₹3,000 in stock"))
                .isEqualTo(filters(List.of("red", "running"), "shoes", null, 300_000L, true));
        assertThat(parse("kitchen stuff between 500 and 1500"))
                .isEqualTo(filters(List.of("stuff"), "kitchen", 50_000L, 150_000L, null));
        assertThat(parse("bag 2k-1k")).isEqualTo(filters(List.of(), "bags", 100_000L, 200_000L, null));
        assertThat(parse("Rs. 999 or more than 200 shoe")).isEqualTo(filters(List.of(), "shoes", 20_000L, null, null));
    }

    @Test
    void handlesManyPhrasings() {
        assertThat(parse("show me a laptop backpack below rs 2500").maxPricePaise()).isEqualTo(250_000L);
        assertThat(parse("kettle < 1200").maxPricePaise()).isEqualTo(120_000L);
        assertThat(parse("pan over 1000").minPricePaise()).isEqualTo(100_000L);
        assertThat(parse("anything at least 300 available"))
                .isEqualTo(filters(List.of("anything"), null, 30_000L, null, true));
        assertThat(parse("1000 to 2000 inr shoes").minPricePaise()).isEqualTo(100_000L);
    }

    @Test
    void capsAndDeDuplicatesTermsAndSkipsStopwords() {
        assertThat(parse("the best new cotton cotton tee for men with pockets and zip and hood").terms())
                .containsExactly("cotton", "tee", "men", "pockets", "zip");
        assertThat(parse("12345 678").terms()).isEmpty();
    }

    @Test
    void capsPrices() {
        assertThat(parse("under 99999999").maxPricePaise()).isEqualTo(100_000_000L);
        assertThat(parse("under 99999999999999999999999").maxPricePaise()).isEqualTo(100_000_000L);
    }

    @Test
    void matchesCategoriesWithPlurals() {
        assertThat(RuleParser.matchCategory("shoe", CATS)).isEqualTo("shoes");
        assertThat(RuleParser.matchCategory("bag", CATS)).isEqualTo("bags");
        assertThat(RuleParser.matchCategory("kitchens", CATS)).isEqualTo("kitchen");
        assertThat(RuleParser.matchCategory("hat", CATS)).isNull();
    }

    @Test
    void acceptsACleanLlmAnswer() {
        String raw = "Sure! {\"terms\": [\"Red\", \"red\", \"running\"], \"category\": \"shoes\", \"minPrice\": null, "
                + "\"maxPrice\": 3000, \"inStock\": true}";
        assertThat(LlmParser.parseFilters(raw, CATS))
                .isEqualTo(filters(List.of("red", "running"), "shoes", null, 300_000L, true));
        assertThat(LlmParser.parseFilters("{\"terms\": [], \"inStock\": false}", CATS))
                .isEqualTo(filters(List.of(), null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "no json here",
        "[\"a\"]",
        "{\"terms\": \"shoes\"}",
        "{\"terms\": [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\"]}",
        "{\"terms\": [\"DROP TABLE products;\"]}",
        "{\"category\": \"weapons\"}",
        "{\"maxPrice\": -1}",
        "{\"maxPrice\": 1.5}",
        "{\"maxPrice\": true}",
        "{\"minPrice\": 500, \"maxPrice\": 100}",
        "{\"inStock\": \"yes\"}"})
    void rejectsAnythingUnexpectedFromTheLlm(String raw) {
        assertThatThrownBy(() -> LlmParser.parseFilters(raw, CATS)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void usesTheLlmAndFallsBack() {
        FakeChat llm = new FakeChat("{\"terms\": [\"sneakers\"], \"category\": \"shoes\", \"maxPrice\": 2000}");
        SearchAssistService.Assist result = new SearchAssistService(new RuleParser(), new LlmParser(llm))
                .interpret("sneakers under 2000", CATS);
        assertThat(List.of(result.provider(), result.fallback(), result.filters().terms()))
                .containsExactly("llm", false, List.of("sneakers"));
        assertThat(llm.calls.get(0)[0]).contains("Allowed categories: bags, kitchen, shoes");
        assertThat(llm.calls.get(0)[1]).isEqualTo("<query>\nsneakers under 2000\n</query>");
        SearchAssistService broken = new SearchAssistService(new RuleParser(),
                new LlmParser(new FakeChat("{\"category\": \"weapons\"}")));
        result = broken.interpret("sneakers under 2000", CATS);
        assertThat(List.of(result.provider(), result.fallback(), result.filters().maxPricePaise()))
                .containsExactly("rules", true, 200_000L);
        SearchAssistService.Assist offline = new SearchAssistService(new RuleParser(), null).interpret("bags", CATS);
        assertThat(List.of(offline.provider(), offline.fallback())).containsExactly("rules", false);
    }
}
