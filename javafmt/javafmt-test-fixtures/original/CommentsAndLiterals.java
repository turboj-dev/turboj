/* Original JavaFMT fixture. Preserve this header and its spacing. */
public class CommentsAndLiterals{
/** Computes {@code a + b}. <pre>
 * int x=1; // intentionally preserve the example
 * </pre>
 * @param a first value
 * @param b second value
 * @return the sum
 */
int sum(int a,/* second */int b){return a /* operator */ + b;}// trailing method note
String value="quote: \" slash: \\ space: \s";
char marker='\n';
String block="""
    leading spaces matter
      extra indentation\s
    """;
}
