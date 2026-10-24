package dev.turboj.refactorj.api;

/** Safe plain-text rendering; no external resources or executable markup. */
public final class DocumentationText {
    private DocumentationText() {}
    public static String plainJavadoc(String comment) {
        String text =
                comment.replaceFirst("^/\\*\\*", "")
                        .replaceFirst("\\*/$", "")
                        .replaceAll("(?m)^\\s*\\* ?", "");
        text =
                java.util.regex.Pattern.compile("\\{@(?:code|literal)\\s+([^}]*)}")
                        .matcher(text)
                        .replaceAll(
                                match ->
                                        java.util.regex.Matcher.quoteReplacement(
                                                match.group(1)
                                                        .replace("&", "&amp;")
                                                        .replace("<", "&lt;")
                                                        .replace(">", "&gt;")));
        text =
                text.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", "")
                        .replaceAll("(?i)<(?:p|br|/p|/div|/li|/tr|/pre)\\b[^>]*>", "\n")
                        .replaceAll("(?i)<li\\b[^>]*>", "\n• ")
                        .replaceAll("(?s)<[^>]*>", "");
        text =
                text.replaceAll("\\{@(?:code|literal)\\s+([^}]*)}", "$1")
                        .replaceAll("\\{@(?:link|linkplain)\\s+([^}]*)}", "$1")
                        .replace("{@inheritDoc}", "")
                        .replaceAll("(?m)^\\s*@param\\s+", "Parameter: ")
                        .replaceAll("(?m)^\\s*@return\\s+", "Returns: ")
                        .replaceAll("(?m)^\\s*@(?:throws|exception)\\s+", "Throws: ")
                        .replaceAll("(?m)^\\s*@deprecated\\s+", "Deprecated: ")
                        .replaceAll("(?m)^\\s*@since\\s+", "Since: ")
                        .replaceAll("(?m)^\\s*@see\\s+", "See: ")
                        .replaceAll("(?m)^\\s*@implNote\\s+", "Implementation note: ")
                        .replaceAll("(?m)^\\s*@apiNote\\s+", "API note: ");
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

}
