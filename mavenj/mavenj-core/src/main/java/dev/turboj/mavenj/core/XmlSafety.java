package dev.turboj.mavenj.core;

import org.codehaus.plexus.util.ReaderFactory;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Reject DTDs before the official Maven readers see input, including UTF-16 XML. */
final class XmlSafety {
    static void check(Path file) throws IOException {
        if (Files.isRegularFile(file))
            try (InputStream in = Files.newInputStream(file)) {
                check(in);
            }
    }

    static void check(InputStream input) throws IOException {
        try (Reader reader = ReaderFactory.newXmlReader(input)) {
            char[] buffer = new char[4096];
            String tail = "";
            int count;

            while ((count = reader.read(buffer)) >= 0) {
                String text = tail + new String(buffer, 0, count);

                if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE"))
                    throw new IOException("DOCTYPE is not allowed in Maven configuration");
                tail = text.substring(Math.max(0, text.length() - 16));
            }
        }
    }
}
