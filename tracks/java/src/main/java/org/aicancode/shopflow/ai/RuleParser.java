package org.aicancode.shopflow.ai;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        for (String c : categories) {
            if (token.equals(c) || (token + "s").equals(c)
                    || (token.endsWith("s") && token.substring(0, token.length() - 1).equals(c))) {
                return c;
            }
        }
        return null;
    }

    @Override
    public SearchFilters parse(String query, List<String> categories) {
        String text = query.toLowerCase(Locale.ROOT).replace("₹", " ");
        text = THOUSANDS.matcher(text).replaceAll("");
        text = MONEY_WORDS.matcher(text).replaceAll(" ");
        text = K.matcher(text).replaceAll(m -> number(m.group(1)).multiply(BigInteger.valueOf(1000)).toString());
        Long lo = null;
        Long hi = null;
        Matcher m = BETWEEN.matcher(text);
        if (!m.find()) {
            m = RANGE.matcher(text);
            m = m.find() ? m : null;
        }
        if (m != null) {
            BigInteger a = number(m.group(1));
            BigInteger b = number(m.group(2));
            lo = paise(a.min(b));
            hi = paise(a.max(b));
            text = cut(text, m);
        }
        m = MAX.matcher(text);
        if (m.find()) {
            hi = paise(number(m.group(1)));
            text = cut(text, m);
        }
        m = MIN.matcher(text);
        if (m.find()) {
            lo = paise(number(m.group(1)));
            text = cut(text, m);
        }
        Boolean inStock = null;
        m = IN_STOCK.matcher(text);
        if (m.find()) {
            inStock = true;
            text = cut(text, m);
        }
        String category = null;
        List<String> terms = new ArrayList<>();
        Matcher tokens = TOKEN.matcher(text);
        while (tokens.find()) {
            String token = tokens.group();
            String found = category == null ? matchCategory(token, categories) : null;
            if (found != null) {
                category = found;
            } else if (!STOPWORDS.contains(token) && !DIGITS.matcher(token).matches() && !terms.contains(token)
                    && token.length() <= 30) {
                terms.add(token);
            }
        }
        return new SearchFilters(List.copyOf(terms.subList(0, Math.min(terms.size(), MAX_TERMS))), category, lo, hi,
                inStock);
    }
}
