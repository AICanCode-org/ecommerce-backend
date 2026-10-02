package org.aicancode.shopflow.ai;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aicancode.shopflow.domain.Models.SearchFilters;

/** Deterministic and offline. The patterns and their order are part of the spec (identical in every track). */
public class RuleParser implements Parser {

    public static final int MAX_TERMS = 5;
    public static final long MAX_RUPEES = 1_000_000; // ₹10 lakh = Validation.MAX_PRICE_PAISE
    public static final Set<String> STOPWORDS = Set.of(("a an the for with and or of in on to me my show find i want "
            + "need some any please cheap best good new buy looking something that is are price priced rs inr rupee rupees "
            + "under below over above between from max min upto up less more than at least within").split(" "));
    private static final Pattern MONEY_WORDS = Pattern.compile("\\b(?:rs\\.?|inr|rupees?)(?=\\s|\\d|$)");
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d)");
    private static final Pattern K = Pattern.compile("\\b(\\d+)k\\b");
    private static final Pattern BETWEEN = Pattern.compile("\\bbetween\\s+(\\d+)\\s+(?:and|to)\\s+(\\d+)\\b");
    private static final Pattern RANGE = Pattern.compile("\\b(\\d+)\\s*(?:-|to)\\s*(\\d+)\\b");
    private static final Pattern MAX =
            Pattern.compile("(?:\\b(?:under|below|less than|up to|upto|max|within)\\s+|<\\s*)(\\d+)\\b");
    private static final Pattern MIN =
            Pattern.compile("(?:\\b(?:over|above|more than|from|min|at least)\\s+|>\\s*)(\\d+)\\b");
    private static final Pattern IN_STOCK = Pattern.compile("\\b(?:in stock|available)\\b");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private static BigInteger number(String digits) {
        return new BigInteger(digits);
    }

    private static long paise(BigInteger rupees) {
        return rupees.min(BigInteger.valueOf(MAX_RUPEES)).longValue() * 100;
    }

    private static String cut(String text, Matcher m) {
        return text.substring(0, m.start()) + " " + text.substring(m.end());
    }

    /** "shoe" and "shoes" both match the category "shoes"; "bags" matches "bag". */
    public static String matchCategory(String token, List<String> categories) {
        // TODO(phase-3 M3): 'shoe' and 'shoes' both match the category 'shoes'; 'bags' matches 'bag': token
        //   == c, token + 's' == c, or token minus a trailing 's' == c.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RuleParser.matchCategory");
    }

    @Override
    public SearchFilters parse(String query, List<String> categories) {
        // TODO(phase-3 M3): deterministic, the order is part of the spec: lower-case, drop ₹ and thousands
        //   separators and money words, '3k' -> 3000; then between/range, under/max, over/min (whole rupees ->
        //   paise, capped at MAX_RUPEES), 'in stock'; the first token that matches a category is the category;
        //   other tokens (not stopwords, not numbers, unique, <= 30 chars) are terms, at most MAX_TERMS.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RuleParser.parse");
    }
}
