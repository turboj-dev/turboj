/**
 * Renders the welcome mail.
 *
 * <pre>
 *   Templates.welcome("Ada")   // keeps this example exactly as written
 * </pre>
 */
public class Templates {
// The text block below must keep every space and line exactly.
static final String WELCOME = """
    Dear %s,

      welcome to TurboJ.   Two spaces here, three there.
    \tA tab, and a line that ends with a backslash \
    continued.
    """;

static String welcome(String name) { /* inline comment stays put */ return WELCOME.formatted(name); }

static String query(String table) {
return """
SELECT id, name
  FROM %s
 WHERE active = true
""".formatted(table); // trailing comment on the closing line
}
}
