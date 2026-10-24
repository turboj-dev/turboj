package dev.turboj.mavenj.workspace;

import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Lossless lexical edits, validated as XML. Maven's model builder remains the semantic authority. */
public final class PomEdits {
    private PomEdits() {}

    /** UTF-16 offsets, matching String and editor documents. */
    public record Edit(int start, int end, String text) {}

    /** A lexical XML element for POM navigation; no Maven inheritance semantics are inferred here. */
    public record Element(List<String> path, int start, int end, int contentStart, int contentEnd,
            String text) {
        public Element {
            path = List.copyOf(path);
        }
    }

    public static List<Element> elements(String xml) {
        var result = new ArrayList<Element> ();
        collect(xml, parse(xml), List.of(), result);
        return List.copyOf(result);
    }

    private static void collect(String xml, Node node, List<String> parent, List<Element> result) {
        var path = new ArrayList<> (parent);
        path.add(node.local());
        result.add(new Element(path, node.start, node.end, node.openEnd, node.closeStart,
                node.children.isEmpty() ? text(xml, node) : ""));
        for (Node child : node.children)
            collect(xml, child, path, result);
    }

    public record Plan(String original, String result, List<Edit> edits, String description) {
        public Plan {
            edits = List.copyOf(edits);
        }

        public String applyTo(String current) {
            if (!original.equals(current))
                throw new IllegalStateException("POM changed after preview");
            return result;
        }
    }

    private static final class Node {
        String name;
        int start, openEnd, closeStart, end;
        boolean empty;
        Node parent;
        final List<Node> children = new ArrayList<> ();

        String local() {
            int colon = name.indexOf(':');
            return name.substring(colon + 1);
        }

        Node child(String name) {
            List<Node> matches = children.stream().filter(n -> n.local().equals(name)).toList();

            if (matches.size() > 1)
                throw new IllegalArgumentException("Ambiguous duplicate <" + name + "> element");
            return matches.isEmpty() ? null : matches.getFirst();
        }
    }

    public static Plan addDependency(String xml, String group, String artifact, String version,
            String scope) {
        requireCoordinate(group);
        requireCoordinate(artifact);
        Node root = parse(xml);
        Node dependencies = root.child("dependencies");

        if (dependencies != null && dependency(dependencies, xml, group, artifact, false) != null)
            throw new IllegalArgumentException("Dependency already exists; use Update Dependency Version");
        String prefix = prefix(root), nl = newline(xml), unit = indentation(xml, root);
        String content = tag(prefix, "groupId", group) + nl + tag(prefix, "artifactId", artifact);

        if (version != null && !version.isBlank())
            content += nl + tag(prefix, "version", version);
        if (scope != null && !scope.isBlank() && !scope.equals("compile")) {
            if (!Set.of("provided", "runtime", "test", "system", "import").contains(scope))
                throw new IllegalArgumentException("Invalid dependency scope");
            if (scope.equals("system") || scope.equals("import"))
                throw new IllegalArgumentException("System paths and BOM imports require their dedicated Maven configuration");
            content += nl + tag(prefix, "scope", scope);
        }
        String block = "<" + prefix + "dependency>" + nl + indent(content, unit) + nl + "</"
                + prefix + "dependency>";

        if (dependencies == null)
            block = "<" + prefix + "dependencies>" + nl + indent(block, unit) + nl + "</" + prefix
                    + "dependencies>";
        return insert(xml, dependencies == null ? root : dependencies, block, unit,
                "Add dependency " + group + ":" + artifact);
    }

    public static Plan updateDependencyVersion(String xml, String group, String artifact,
            String version) {
        requireCoordinate(group);
        requireCoordinate(artifact);
        requireValue(version);
        Node root = parse(xml);
        Node dependencies = root.child("dependencies");

        if (dependencies == null)
            throw new IllegalArgumentException("No direct dependencies in this POM");
        Node dep = dependency(dependencies, xml, group, artifact, true);
        Node existing = dep.child("version");

        if (existing == null)
            return insert(xml, dep, tag(prefix(dep), "version", version), indentation(xml, root),
                    "Override managed version for " + group + ":" + artifact);
        String old = text(xml, existing);

        if (old.startsWith("${") && old.endsWith("}") && old.indexOf('}') == old.length() - 1) {
            String name = old.substring(2, old.length() - 1);
            Node properties = root.child("properties"), property = properties
                    == null ? null : properties.child(name);

            if (property == null)
                throw new IllegalArgumentException("Version property " + old
                        + " is inherited or external; edit its declaration");
            return replaceContent(xml, property, version, "Update property " + old
                    + " (affects every use in this POM)");
        }
        if (old.contains("${"))
            throw new IllegalArgumentException("Composite version expression must be edited at its property declarations");
        return replaceContent(xml, existing, version, "Update dependency " + group + ":"
                + artifact);
    }

    public static Plan removeDependency(String xml, String group, String artifact) {
        Node root = parse(xml);
        Node dependencies = root.child("dependencies");

        if (dependencies == null)
            throw new IllegalArgumentException("No direct dependencies in this POM");
        Node dep = dependency(dependencies, xml, group, artifact, true);
        int start = dep.start, end = dep.end;
        int line = xml.lastIndexOf('\n', start - 1) + 1;

        if (xml.substring(line, start).isBlank()) {
            int next = xml.indexOf('\n', end);

            if (next >= 0 && xml.substring(end, next).isBlank()) {
                start = line;
                end = next + 1;
            }
        }
        return plan(xml, new Edit(start, end, ""), "Remove dependency " + group + ":" + artifact);
    }

    public static Plan addModule(String xml, String path) {
        requireValue(path);
        if (path.contains("${") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use a literal module path");
        Node root = parse(xml), modules = root.child("modules");

        if (modules != null && modules.children.stream().filter(n -> n.local().equals("module"))
                .anyMatch(n -> text(xml, n).equals(path)))
            throw new IllegalArgumentException("Module already exists");
        String unit = indentation(xml, root), nl = newline(xml), block = tag(prefix(root), "module",
                path);

        if (modules == null)
            block = "<" + prefix(root) + "modules>" + nl + indent(block, unit) + nl + "</"
                    + prefix(root) + "modules>";
        return insert(xml, modules == null ? root : modules, block, unit, "Add module " + path);
    }

    private static Plan replaceContent(String xml, Node node, String value, String description) {
        if (!node.children.isEmpty())
            throw new IllegalArgumentException("Expected a simple text element");
        if (node.empty)
            return plan(xml, new Edit(node.start, node.end, tag(prefix(node), node.local(), value)),
                    description);
        String old = xml.substring(node.openEnd, node.closeStart);

        if (old.contains("<!--") || old.contains("<![CDATA["))
            throw new IllegalArgumentException("Value contains XML markup; edit it explicitly to preserve comments");
        int left = 0, right = old.length();

        while (left < right && Character.isWhitespace(old.charAt(left)))
            left++;
        while (right > left && Character.isWhitespace(old.charAt(right - 1)))
            right--;
        return plan(xml, new Edit(node.openEnd + left, node.openEnd + right, escape(value)),
                description);
    }

    private static Node dependency(Node parent, String xml, String group, String artifact,
            boolean required) {
        var matches = parent.children.stream().filter(n -> n.local().equals("dependency"))
                .filter(n -> group.equals(text(xml, n.child("groupId"))) && artifact
                .equals(text(xml, n.child("artifactId")))).toList();

        if (matches.size() > 1)
            throw new IllegalArgumentException("Multiple classifiers/types match; edit the exact dependency declaration");
        if (matches.isEmpty() && required)
            throw new IllegalArgumentException("Dependency not declared directly in this POM");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static Plan insert(String xml, Node parent, String block, String unit,
            String description) {
        String nl = newline(xml), parentIndent = lineIndent(xml, parent.start),
                childIndent = parentIndent + unit;

        if (parent.empty) {
            String opening = xml.substring(parent.start, parent.openEnd).replaceFirst("/\\s*>$",
                    ">");
            String value = opening + nl + indent(block, childIndent) + nl + parentIndent + "</"
                    + parent.name + ">";
            return plan(xml, new Edit(parent.start, parent.end, value), description);
        }
        int offset = parent.closeStart;
        String before = xml.substring(xml.lastIndexOf('\n', offset - 1) + 1, offset);

        if (before.isBlank()) {
            offset -= before.length();
            return plan(xml, new Edit(offset, offset, indent(block, childIndent) + nl),
                    description);
        }
        return plan(xml, new Edit(offset, offset, nl + indent(block, childIndent) + nl
                + parentIndent), description);
    }

    private static Plan plan(String xml, Edit edit, String description) {
        String result = xml.substring(0, edit.start) + edit.text + xml.substring(edit.end);
        validate(result);
        return new Plan(xml, result, List.of(edit), description);
    }

    private static String prefix(Node node) {
        int colon = node.name.indexOf(':');
        return colon < 0 ? "" : node.name.substring(0, colon + 1);
    }

    private static String tag(String prefix, String name, String value) {
        return "<" + prefix + name + ">" + escape(value) + "</" + prefix + name + ">";
    }

    private static String escape(String value) {
        requireValue(value);
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String newline(String xml) {
        return xml.contains("\r\n") ? "\r\n" : "\n";
    }

    private static String indent(String value, String indent) {
        return indent + value.replace("\n", "\n" + indent);
    }

    private static String lineIndent(String xml, int offset) {
        String prefix = xml.substring(xml.lastIndexOf('\n', offset - 1) + 1, offset);
        return prefix.isBlank() ? prefix : "";
    }

    private static String indentation(String xml, Node root) {
        for (Node child : root.children) {
            String indent = lineIndent(xml, child.start);

            if (!indent.isEmpty())
                return indent;
        }
        return "  ";
    }

    private static void requireValue(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("A non-empty value is required");
    }

    private static void requireCoordinate(String value) {
        requireValue(value);
        if (!value.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Use literal Maven coordinates");
    }

    private static String text(String xml, Node node) {
        if (node == null || node.empty)
            return "";
        try {
            return builder().newDocumentBuilder().parse(new InputSource(new StringReader("<v>" + xml
                    .substring(node.openEnd, node.closeStart) + "</v>"))).getDocumentElement()
                    .getTextContent().trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML text", e);
        }
    }

    private static DocumentBuilderFactory builder() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    public static void validate(String xml) {
        if (xml == null || xml.length() > 4_000_000)
            throw new IllegalArgumentException("POM exceeds the 4 MB edit limit");
        try {
            var parser = builder().newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXParseException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXParseException {
                    throw e;
                }
            });
            var root = parser.parse(new InputSource(new StringReader(xml))).getDocumentElement();

            if (!root.getLocalName().equals("project"))
                throw new IllegalArgumentException("Expected a Maven <project>");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid POM XML: " + e.getMessage(), e);
        }
    }

    private static Node parse(String xml) {
        validate(xml);
        var stack = new ArrayDeque<Node> ();
        Node root = null;

        for (int i = 0; i < xml.length();) {
            int start = xml.indexOf('<', i);

            if (start < 0)
                break;
            if (xml.startsWith("<!--", start)) {
                i = xml.indexOf("-->", start + 4) + 3;
                continue;
            }
            if (xml.startsWith("<![CDATA[", start)) {
                i = xml.indexOf("]]>", start + 9) + 3;
                continue;
            }
            if (xml.startsWith("<?", start)) {
                i = xml.indexOf("?>", start + 2) + 2;
                continue;
            }
            int end = start + 1;
            char quote = 0;

            while (end < xml.length()) {
                char c = xml.charAt(end);

                if (quote != 0) {
                    if (c == quote)
                        quote = 0;
                } else if (c == '\'' || c == '"')
                    quote = c;
                else if (c == '>')
                    break;
                end++;
            }
            i = end + 1;
            if (xml.charAt(start + 1) == '/') {
                Node node = stack.pop();
                node.closeStart = start;
                node.end = i;
                continue;
            }
            int nameEnd = start + 1;

            while (nameEnd < end && !Character.isWhitespace(xml.charAt(nameEnd)) && xml
                    .charAt(nameEnd) != '/')
                nameEnd++;
            Node node = new Node();
            node.name = xml.substring(start + 1, nameEnd);
            node.start = start;
            node.openEnd = i;
            node.empty = xml.charAt(end - 1) == '/';
            node.parent = stack.peek();
            if (node.parent != null)
                node.parent.children.add(node);
            else
                root = node;
            if (node.empty) {
                node.closeStart = i;
                node.end = i;
            } else
                stack.push(node);
        }
        return Objects.requireNonNull(root);
    }
}
