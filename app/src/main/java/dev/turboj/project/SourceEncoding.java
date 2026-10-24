package dev.turboj.project;

import dev.turboj.mavenj.api.MavenModels.Workspace;
import java.nio.charset.*;
import java.nio.file.Path;

/** Retains file encodings across workspace changes and Save As; Maven governs Java destinations. */
public final class SourceEncoding {
    private SourceEncoding() {}
    public record Encoding(Charset charset, byte[] bom) {
        public Encoding { bom = bom.clone(); }
        @Override public byte[] bom() { return bom.clone(); }
    }
    public static Encoding select(Workspace workspace, Path destination, Encoding loaded, Encoding origin) {
        Encoding remembered = loaded == null ? origin : loaded;
        Charset charset = remembered == null ? StandardCharsets.UTF_8 : remembered.charset();
        if (workspace != null && destination.getFileName().toString().endsWith(".java")) {
            var module = workspace.moduleFor(destination);
            if (module.isPresent()) charset = Charset.forName((module.get().isTest(destination) ? module.get().testJava() : module.get().mainJava()).encoding());
        }
        return new Encoding(charset, remembered != null && remembered.charset().equals(charset) ? remembered.bom() : new byte[0]);
    }
}
