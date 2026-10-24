package dev.turboj.ui;

import dev.turboj.compiler.CompilerDiagnostic;
import java.io.StringReader;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Passive XML validation. External entities, DTDs and schema downloads are disabled. */
public final class XmlDiagnostics {
    private XmlDiagnostics() {}

    public static List<CompilerDiagnostic> inspect(String source) {
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.parse(new InputSource(new StringReader(source)), new DefaultHandler() {
                @Override public void error(SAXParseException error) throws SAXParseException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXParseException { throw error; }
            });
            return List.of();
        } catch (SAXParseException error) {
            int line = Math.max(1, error.getLineNumber()), column = Math.max(1, error.getColumnNumber());
            int start = 0;
            for (int count = 1; count < line && start < source.length(); count++) {
                int newline = source.indexOf('\n', start);
                start = newline < 0 ? source.length() : newline + 1;
            }
            start = Math.min(source.length(), start + column - 1);
            return List.of(new CompilerDiagnostic("ERROR", error.getMessage(), line, column, start, Math.min(source.length(), start + 1)));
        } catch (Exception error) {
            return List.of(new CompilerDiagnostic("ERROR", "XML validation: " + error.getMessage(), 1, 1, 0, Math.min(1, source.length())));
        }
    }
}
