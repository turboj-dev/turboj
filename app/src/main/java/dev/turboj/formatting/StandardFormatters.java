package dev.turboj.formatting;

import com.google.common.collect.Range;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.google.googlejavaformat.java.JavaFormatterOptions;
import dev.javafmt.core.FormatDiagnostic;
import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.FormatResult;
import dev.javafmt.core.JavaFormatter;
import dev.javafmt.core.TextEdit;
import dev.javafmt.core.TextRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;

/**
 * The Java styles TurboJ formats with (docs/formatting.md). The default is IntelliJ IDEA's default code style,
 * reproduced on the bundled Eclipse JDT formatter, as are Eclipse and AWS. google-java-format provides its two styles,
 * Google and AOSP, and two TurboJ presets on AOSP with another line width, Classic 80 and Wide 120. JavaFMT, TurboJ's own engine, is on hold:
 * its code stays and a saved choice still works, but it is no longer offered. Every engine returns whitespace-precise
 * edits so the caret and undo stay put.
 */
public enum StandardFormatters {
    AOSP("aosp", "AOSP", "google-java-format, AOSP style · 4 spaces · 100 columns", 4, 100, Group.GOOGLE_JAVA_FORMAT),
    CLASSIC_80("classic80", "Classic 80", "TurboJ preset: google-java-format AOSP at 80 columns · 4 spaces", 4, 80, Group.GOOGLE_JAVA_FORMAT),
    WIDE_120("wide120", "Wide 120", "TurboJ preset: google-java-format AOSP at 120 columns · 4 spaces", 4, 120, Group.GOOGLE_JAVA_FORMAT),
    GOOGLE("google", "Google", "google-java-format, Google style · 2 spaces · 100 columns", 2, 100, Group.GOOGLE_JAVA_FORMAT),
    ECLIPSE("eclipse", "Eclipse", "Eclipse built-in profile (spaces) · 4 spaces · 120 columns", 4, 120, Group.OTHER),
    INTELLIJ("intellij", "IntelliJ IDEA", "IntelliJ IDEA default code style, on the Eclipse engine · 4 spaces · keeps line breaks", 4, 0, Group.OTHER),
    AWS("aws", "AWS (Smithy)", "the Eclipse profile AWS's Smithy projects enforce · 4 spaces · 120 columns", 4, 120, Group.OTHER),
    JAVAFMT("javafmt", "JavaFMT (on hold)", "TurboJ's JavaFMT Style 1, on hold · 4 spaces · 100 columns", 4, 100, Group.ON_HOLD);

    /** The active engine's presets, the other engines, and engines kept but no longer offered. */
    public enum Group { GOOGLE_JAVA_FORMAT, OTHER, ON_HOLD }

    /** TurboJ's default for now: IntelliJ IDEA's default code style, four spaces, line breaks kept as written. */
    public static final StandardFormatters DEFAULT = INTELLIJ;
    /** The google-java-format release TurboJ pins (app/pom.xml). */
    public static final String GOOGLE_JAVA_FORMAT_VERSION = "1.37.0";

    private final String id, title, description;
    private final int indent, width;
    private final Group group;

    StandardFormatters(String id, String title, String description, int indent, int width, Group group) {
        this.id = id; this.title = title; this.description = description; this.indent = indent; this.width = width; this.group = group;
    }

    public String id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    /** Spaces per block: the editor indents as the formatter will. */
    public int indent() { return indent; }
    /** The line width the engine aims at; 0 when it never wraps (IntelliJ's defaults). */
    public int width() { return width; }
    public Group group() { return group; }
    /** Offered in the menu and in Settings: everything but the engines on hold. */
    public boolean offered() { return group != Group.ON_HOLD; }

    public static StandardFormatters of(String id) {
        for (var value : values()) if (value.id.equalsIgnoreCase(String.valueOf(id))) return value;
        return DEFAULT;
    }

    /** A fresh engine; it runs on the formatting worker thread only. */
    public FormattingController.Engine engine() {
        return switch (this) {
            case GOOGLE, AOSP, CLASSIC_80, WIDE_120 -> new Google(googleOptions(), title);
            case ECLIPSE -> new Eclipse(title, StandardFormatters::eclipseBuiltIn);
            case INTELLIJ -> new Eclipse(title, StandardFormatters::intellijDefaults);
            case AWS -> new Eclipse(title, () -> profile("aws-smithy-eclipse-formatter.xml"));
            case JAVAFMT -> new JavaFmt();
        };
    }

    /**
     * The options for this preset. google-java-format 1.37.0 carries the width in {@link JavaFormatterOptions.Style};
     * Classic 80 and Wide 120 are its AOSP style with another {@code maxLineLength}, the API's counterpart of the CLI's
     * {@code --max-line-length}. Javadoc is left as written and modifiers keep their order: formatting changes layout only.
     */
    JavaFormatterOptions googleOptions() {
        var style = (this == GOOGLE ? JavaFormatterOptions.Style.GOOGLE : JavaFormatterOptions.Style.AOSP).toBuilder().maxLineLength(width).build();
        return JavaFormatterOptions.builder().style(style).formatJavadoc(false).reorderModifiers(false).build();
    }

    /**
     * google-java-format through its Java API, in process. {@code formatSource} only lays code out: unlike the CLI's
     * default, it neither sorts nor removes imports and does not split long strings. Organize Imports stays separate.
     */
    private static final class Google implements FormattingController.Engine {
        private final Formatter formatter;
        private final String label;

        Google(JavaFormatterOptions options, String label) { this.formatter = new Formatter(options); this.label = label; }

        @Override public String label() { return label; }

        @Override public FormatResult format(FormatRequest request, TextRange range) {
            String source = request.source();
            try {
                // A selection formats its lines within the whole file, so the file's context is kept.
                String formatted = range == null ? formatter.formatSource(source)
                        : formatter.formatSource(source, List.of(Range.closedOpen(range.start(), Math.max(range.start() + 1, range.end()))));
                return result(request, formatted, label, range);
            } catch (FormatterException invalid) {
                String message = invalid.diagnostics().isEmpty() ? invalid.getMessage() : invalid.diagnostics().getFirst().toString();
                return failure(request, label, message);
            } catch (IllegalAccessError closed) {
                return failure(request, label, "google-java-format needs the JDK compiler exports; start TurboJ with ./turboj");
            }
        }
    }

    /** The Eclipse IDE's own default profile; TurboJ indents with spaces where Eclipse uses tabs. */
    private static Map<String, String> eclipseBuiltIn() {
        Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
        return options;
    }

    /**
     * IntelliJ IDEA's default Java code style: 4-space indent, 8-space continuation, case indented in switch, two blank
     * lines kept, and, as IntelliJ's "Do not wrap" defaults and "Keep line breaks" do, no line is ever split or joined
     * and comments are left alone. Imports keep their order (IntelliJ sorts them only on Optimize Imports).
     */
    private static Map<String, String> intellijDefaults() {
        Map<String, String> options = eclipseBuiltIn();
        options.put(DefaultCodeFormatterConstants.FORMATTER_CONTINUATION_INDENTATION, "2");
        options.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, "9999");
        options.put(DefaultCodeFormatterConstants.FORMATTER_JOIN_WRAPPED_LINES, DefaultCodeFormatterConstants.FALSE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_SWITCH, DefaultCodeFormatterConstants.TRUE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_NUMBER_OF_EMPTY_LINES_TO_PRESERVE, "2");
        for (String comments : List.of(DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_JAVADOC_COMMENT,
                DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_BLOCK_COMMENT, DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_LINE_COMMENT))
            options.put(comments, DefaultCodeFormatterConstants.FALSE);
        return options;
    }

    /** An exported Eclipse formatter profile (the XML Eclipse, IntelliJ's Eclipse importer and Spotless all read). */
    static Map<String, String> profile(String resource) {
        Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        try (var in = StandardFormatters.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing formatter profile " + resource);
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var settings = factory.newDocumentBuilder().parse(in).getElementsByTagName("setting");
            for (int i = 0; i < settings.getLength(); i++) {
                var setting = (org.w3c.dom.Element) settings.item(i);
                options.put(setting.getAttribute("id"), setting.getAttribute("value"));
            }
        } catch (java.io.IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException invalid) {
            throw new IllegalStateException("Unreadable formatter profile " + resource, invalid);
        }
        return options;
    }

    private static final class Eclipse implements FormattingController.Engine {
        private final String label;
        private final java.util.function.Supplier<Map<String, String>> profile;
        private CodeFormatter formatter;
        private String level = "";

        Eclipse(String label, java.util.function.Supplier<Map<String, String>> profile) { this.label = label; this.profile = profile; }

        @Override public String label() { return label; }

        @Override public FormatResult format(FormatRequest request, TextRange range, String languageLevel) {
            if (formatter == null || !level.equals(languageLevel)) {
                Map<String, String> options = profile.get();
                for (String key : List.of(JavaCore.COMPILER_SOURCE, JavaCore.COMPILER_COMPLIANCE, JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM))
                    options.put(key, languageLevel);
                formatter = ToolFactory.createCodeFormatter(options);
                level = languageLevel;
            }
            return format(request, range);
        }

        @Override public FormatResult format(FormatRequest request, TextRange range) {
            if (formatter == null) return format(request, range, "25");
            String source = request.source();
            int start = range == null ? 0 : range.start(), length = range == null ? source.length() : range.end() - range.start();
            String separator = source.contains("\r\n") ? "\r\n" : "\n";
            var edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS, source, start, length, 0, separator);
            if (edit == null) return failure(request, label(), label + " cannot format this source: check its syntax.");
            var document = new org.eclipse.jface.text.Document(source);
            try { edit.apply(document); }
            catch (org.eclipse.jface.text.BadLocationException impossible) { return failure(request, label(), impossible.getMessage()); }
            return result(request, document.get(), label(), range);
        }
    }

    private static final class JavaFmt implements FormattingController.Engine {
        private JavaFormatter formatter;
        private String level = "";

        @Override public String label() { return "JavaFMT Style 1"; }

        @Override public FormatResult format(FormatRequest request, TextRange range, String languageLevel) {
            if (formatter == null || !level.equals(languageLevel)) {
                formatter = new JavaFormatter(new dev.javafmt.parser.JdtJavaParser(languageLevel));
                level = languageLevel;
            }
            return format(request, range);
        }

        @Override public FormatResult format(FormatRequest request, TextRange range) {
            if (formatter == null) formatter = new JavaFormatter();
            return range == null ? formatter.format(request) : formatter.formatRange(request, range);
        }
    }

    static FormatResult result(FormatRequest request, String formatted, String label, TextRange range) {
        return new FormatResult(formatted, edits(request.source(), formatted), request.sourceVersion(), label, 0, List.of(), true,
                range == null ? new TextRange(0, request.source().length()) : range);
    }

    static FormatResult failure(FormatRequest request, String label, String message) {
        return new FormatResult(request.source(), List.of(), request.sourceVersion(), label, 0,
                List.of(new FormatDiagnostic(0, 0, message)), false, null);
    }

    /**
     * The smallest edits turning {@code before} into {@code after}. A formatter changes whitespace between the same tokens,
     * so the edits are the whitespace runs that differ; when the tokens changed too (imports reordered), the changed span
     * becomes one edit.
     */
    static List<TextEdit> edits(String before, String after) {
        if (before.equals(after)) return List.of();
        var edits = new ArrayList<TextEdit>();
        int i = 0, j = 0;
        while (i < before.length() || j < after.length()) {
            int i0 = i, j0 = j;
            while (i < before.length() && Character.isWhitespace(before.charAt(i))) i++;
            while (j < after.length() && Character.isWhitespace(after.charAt(j))) j++;
            if (!before.substring(i0, i).equals(after.substring(j0, j))) edits.add(new TextEdit(i0, i, after.substring(j0, j)));
            if (i >= before.length() || j >= after.length()) {
                if (i < before.length() || j < after.length()) return span(before, after);
                break;
            }
            if (before.charAt(i) != after.charAt(j)) return span(before, after);
            while (i < before.length() && j < after.length() && !Character.isWhitespace(before.charAt(i))
                    && before.charAt(i) == after.charAt(j)) { i++; j++; }
            if (i < before.length() && j < after.length() && !Character.isWhitespace(before.charAt(i))
                    && !Character.isWhitespace(after.charAt(j))) return span(before, after);
        }
        return edits;
    }

    /** One edit covering everything between the common prefix and the common suffix. */
    private static List<TextEdit> span(String before, String after) {
        int prefix = 0, max = Math.min(before.length(), after.length());
        while (prefix < max && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) suffix++;
        return List.of(new TextEdit(prefix, before.length() - suffix, after.substring(prefix, after.length() - suffix)));
    }

    @Override public String toString() { return title.toLowerCase(Locale.ROOT); }
}
