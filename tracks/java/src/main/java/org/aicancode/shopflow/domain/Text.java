package org.aicancode.shopflow.domain;

import java.util.regex.Pattern;

/** Small text helpers shared by the AI features (identical rules in every track). Lengths are code points. */
public final class Text {

    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])(?U)\\s+");

    private Text() {
    }

    public static int chars(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Runs of whitespace -> one space, trimmed. */
    public static String collapse(String text) {
        return SPACES.matcher(text.strip()).replaceAll(" ");
    }

    /** At most `limit` code points: longer text is cut to limit-3 (no trailing space) + "...". */
    public static String shorten(String text, int limit) {
        if (chars(text) <= limit) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, limit - 3)).stripTrailing() + "...";
    }

    public static String firstSentence(String text) {
        String clean = collapse(text);
        return clean.isEmpty() ? "" : SENTENCE_END.split(clean, 2)[0];
    }
}
