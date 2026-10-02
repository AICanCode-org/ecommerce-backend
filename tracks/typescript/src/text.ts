// Small text helpers shared by the AI features (identical rules in every track).

/** Runs of whitespace -> one space, trimmed. */
export const collapse = (text: string) => text.split(/\s+/).filter(Boolean).join(" ");

/** Length in code points (what Python's len() and Java's codePointCount() return), not UTF-16 units. */
export const codePoints = (text: string) => [...text].length;

/** At most `limit` code points: longer text is cut to limit-3 (no trailing space) + '...'. */
export function shorten(text: string, limit: number): string {
  const chars = [...text];
  if (chars.length <= limit) return text;
  return (
    chars
      .slice(0, limit - 3)
      .join("")
      .trimEnd() + "..."
  );
}

export function firstSentence(text: string): string {
  const clean = collapse(text);
  return clean ? (clean.split(/(?<=[.!?])\s+/)[0] ?? "") : "";
}
