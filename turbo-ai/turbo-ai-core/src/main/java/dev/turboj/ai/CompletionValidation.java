package dev.turboj.ai;

/**
 * Conservative insertion-only cleanup. This is not a semantic correctness or security guarantee.
 */
public final class CompletionValidation {
    private CompletionValidation() {}

    public static String usable(AICompletionRequest request, String response, boolean finished) {
        if (response == null || response.length() > 32768 || response.indexOf('\0') >= 0) return "";
        String value = response.replace("\r\n", "\n").replace('\r', '\n');
        if (value.stripLeading().startsWith("```")) {
            int fence = value.indexOf("```");
            // The label line ends at the first line break after the fence, not before it.
            int begin = value.indexOf('\n', fence), last = value.lastIndexOf("```");
            if (begin < 0 || last <= begin || !value.substring(last + 3).isBlank()) return "";
            String label = value.substring(fence + 3, begin).trim();
            if (!label.isEmpty() && !label.equalsIgnoreCase("java")) return "";
            value = value.substring(begin + 1, last);
        }
        // A model that runs past its middle emits its own control tokens: keep what came before them.
        int sentinel = firstSentinel(value);
        if (sentinel >= 0) value = value.substring(0, sentinel);
        if (value.contains("```")) return "";
        String leading = value.stripLeading();
        if (leading.matches(
                "(?is)^(?:here(?:'s| is)|sure[,!]|the (?:code|implementation)|this"
                    + " (?:code|method)|explanation:).*"))
            return "";
        String prefix = request.prefix().replace("\r\n", "\n");
        String suffix = request.suffix().replace("\r\n", "\n");
        if (prefix.length() >= 24
                && value.startsWith(prefix.substring(Math.max(0, prefix.length() - 120))))
            return "";
        if (repeatsPreviousLine(prefix, value)) return "";
        int maxLines = request.larger() ? 32 : 16;
        if (value.lines().count() > maxLines) {
            int cut = 0;
            for (int i = 0; i < maxLines; i++) cut = value.indexOf('\n', cut) + 1;
            value = value.substring(0, cut);
            finished = true;
        }
        // Exact suffix repetition is removed only at the end of a finished response.
        if (finished) {
            int limit = Math.min(value.length(), suffix.length());
            for (int count = limit; count >= 2; count--) {
                if (value.endsWith(suffix.substring(0, count))
                        && (!suffix.substring(0, count).matches("[\\s}]*")
                                || balance(prefix + value + suffix)
                                        < Math.min(0, balance(prefix + suffix)))) {
                    value = value.substring(0, value.length() - count);
                    break;
                }
            }
            // Closing braces duplicated against an already present suffix are common in FIM.
            int baseline = balance(prefix + suffix);
            while (!value.isBlank()
                    && value.stripTrailing().endsWith("}")
                    && suffix.stripLeading().startsWith("}")
                    && balance(prefix + value + suffix) < Math.min(0, baseline)) {
                int last = value.lastIndexOf('}');
                value = value.substring(0, last);
            }
        }
        if (value.isBlank()) return "";
        if (value.endsWith(";;") && !mask(value).endsWith(";;)")) value = value.substring(0, value.length() - 1);
        // Streaming publishes whole statement/block boundaries, never individual model tokens. A
        // finished middle without one (an argument, a chain step, a member name) must leave every
        // parenthesis and bracket of the file as balanced as it was.
        int boundary = coherentBoundary(value);
        if (!finished) value = boundary < 0 ? "" : value.substring(0, boundary);
        else if (boundary < 0
                && !value.trim().matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")
                && parens(prefix + value + suffix) != parens(prefix + suffix))
            return "";
        if (value.isBlank() || unterminatedLiteral(value)) return "";
        if (balance(prefix + value + suffix) < Math.min(0, balance(prefix + suffix))) return "";
        if (request.prefix().contains("\r\n")) value = value.replace("\n", "\r\n");
        return value;
    }

    private static final String[] SENTINELS = {"<|fim_", "<|im_", "<|endoftext|>", "<|file_sep|>", "<|repo_name|>",
        "<fim_prefix>", "<fim_suffix>", "<fim_middle>", "<｜fim", "<|end|>", "<EOT>"};

    private static int firstSentinel(String value) {
        int first = -1;
        for (String sentinel : SENTINELS) {
            int at = value.indexOf(sentinel);
            if (at >= 0 && (first < 0 || at < first)) first = at;
        }
        return first;
    }

    /** A suggestion whose first line is the line just above the caret, again. */
    private static boolean repeatsPreviousLine(String prefix, String value) {
        String first = value.strip().lines().findFirst().orElse("").strip();
        if (first.length() < 6) return false;
        int end = prefix.lastIndexOf('\n');
        if (end < 0) return false;
        int start = prefix.lastIndexOf('\n', end - 1) + 1;
        String previous = prefix.substring(start, end).strip();
        String current = prefix.substring(end + 1).strip();
        return current.isEmpty() && first.equals(previous);
    }

    /** Open parentheses and brackets minus closed ones, outside strings and comments. */
    private static int parens(String text) {
        String plain = mask(text);
        int depth = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
        }
        return depth;
    }

    private static int coherentBoundary(String value) {
        String mask = mask(value);
        int end = -1;
        for (int i = 0; i < mask.length(); i++)
            if (mask.charAt(i) == ';' || mask.charAt(i) == '}') end = i + 1;
        if (end >= 0)
            while (end < value.length() && Character.isWhitespace(value.charAt(end))) end++;
        return end;
    }

    private static int balance(String text) {
        String plain = mask(text);
        int depth = 0;
        for (int i = 0; i < plain.length(); i++) {
            if (plain.charAt(i) == '{') depth++;
            else if (plain.charAt(i) == '}') depth--;
        }
        return depth;
    }

    private static boolean unterminatedLiteral(String value) {
        return lexical(value, null) != 0;
    }

    private static String mask(String value) {
        StringBuilder out = new StringBuilder(value.length());
        lexical(value, out);
        return out.toString();
    }

    /** Masks comments and Java string/character/text-block literals, retaining original offsets. */
    private static int lexical(String value, StringBuilder output) {
        int state = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i), n = i + 1 < value.length() ? value.charAt(i + 1) : 0;
            char emitted = ' ';
            if (state == 0) {
                if (c == '/' && n == '/') state = 1;
                else if (c == '/' && n == '*') state = 2;
                else if (c == '"' && value.startsWith("\"\"\"", i)) {
                    state = 5;
                    if (output != null) output.append("   ");
                    i += 2;
                    continue;
                } else if (c == '"') state = 3;
                else if (c == '\'') state = 4;
                else emitted = c;
            } else if (state == 1 && c == '\n') state = 0;
            else if (state == 2 && c == '*' && n == '/') {
                state = 0;
                if (output != null) output.append("  ");
                i++;
                continue;
            } else if (state == 5 && value.startsWith("\"\"\"", i)) {
                state = 0;
                if (output != null) output.append("   ");
                i += 2;
                continue;
            } else if ((state == 3 || state == 4) && c == '\\') {
                if (output != null) output.append(' ');
                if (i + 1 < value.length()) {
                    i++;
                    if (output != null) output.append(' ');
                }
                continue;
            } else if ((state == 3 && c == '"') || (state == 4 && c == '\'')) state = 0;
            if (output != null) output.append(emitted);
        }
        return state == 1 ? 0 : state;
    }
}
