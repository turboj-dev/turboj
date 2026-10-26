package dev.turboj.project;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.refactorj.api.Model;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesTest {
    @TempDir Path project;

    @Test
    void itemsAreClassifiedByTheirPlaceInTheSourceRoots() throws Exception {
        Path main = Files.createDirectories(project.resolve("src/main/java"));
        Path pkg = Files.createDirectories(main.resolve("com/example"));
        Path resources = Files.createDirectories(project.resolve("src/main/resources"));
        var roots = List.of(main);
        assertEquals(ProjectFiles.Kind.SOURCE_ROOT, ProjectFiles.classify(main, true, roots).kind());
        var node = ProjectFiles.classify(pkg, true, roots);
        assertEquals(ProjectFiles.Kind.PACKAGE, node.kind());
        assertEquals("com.example", node.packageName());
        var unit = ProjectFiles.classify(pkg.resolve("App.java"), false, roots);
        assertEquals(ProjectFiles.Kind.JAVA_FILE, unit.kind());
        assertEquals("com.example", unit.packageName());
        assertEquals(ProjectFiles.Kind.FILE, ProjectFiles.classify(pkg.resolve("notes.txt"), false, roots).kind());
        assertEquals(ProjectFiles.Kind.FOLDER, ProjectFiles.classify(resources, true, roots).kind());
        assertEquals(ProjectFiles.Kind.FOLDER, ProjectFiles.classify(main.resolve("my-assets"), true, roots).kind(),
                "a folder whose name is no Java identifier is not a package");
        assertEquals(ProjectFiles.Kind.FILE, ProjectFiles.classify(main.resolve("my-assets/Odd.java"), false, roots).kind());
    }

    @Test
    void namesAreCheckedBeforeAnythingHappens() {
        assertNull(ProjectFiles.packageProblem("com.example.app"));
        assertEquals("Empty segment in com..app", ProjectFiles.packageProblem("com..app"));
        assertEquals("'class' is a Java keyword", ProjectFiles.packageProblem("com.class"));
        assertEquals("'1st' is not a Java identifier", ProjectFiles.packageProblem("1st"));
        assertNull(ProjectFiles.typeProblem("Calculator"));
        assertEquals("'record' is reserved", ProjectFiles.typeProblem("record"));
        assertNull(ProjectFiles.fileNameProblem("application.properties"));
        assertNotNull(ProjectFiles.fileNameProblem("a/b"));
        assertNotNull(ProjectFiles.fileNameProblem(" padded"));
        assertNotNull(ProjectFiles.fileNameProblem(".."));
    }

    @Test
    void theTypeNamedAfterItsFileAndThePackageDeclarationAreFound() {
        Path file = project.resolve("Calculator.java");
        String source = "package com.example;\n\nclass Helper {}\npublic class Calculator { }\n";
        var type = ProjectFiles.primaryType(file, source).orElseThrow();
        assertEquals("Calculator", source.substring(type.start(), type.end()));
        var declaration = ProjectFiles.packageDeclaration(file, source).orElseThrow();
        assertEquals("com.example", source.substring(declaration.start(), declaration.end()));
        assertTrue(ProjectFiles.primaryType(project.resolve("Other.java"), source).isEmpty(), "no type named Other");
        assertTrue(ProjectFiles.packageDeclaration(file, "class A {}").isEmpty());
    }

    @Test
    void packageTreesSkipFoldersWithoutClassesAndNestedTrees() throws Exception {
        Path main = Files.createDirectories(project.resolve("src"));
        Files.createDirectories(main.resolve("com/example/app/model"));
        Files.createDirectories(main.resolve("com/example/util"));
        Files.writeString(main.resolve("com/example/app/App.java"), "package com.example.app; class App {}");
        Files.writeString(main.resolve("com/example/app/model/M.java"), "package com.example.app.model; class M {}");
        Files.writeString(main.resolve("com/example/util/U.java"), "package com.example.util; class U {}");
        assertEquals(List.of(main.resolve("com/example/app"), main.resolve("com/example/util")), ProjectFiles.packageTrees(main.resolve("com")));
        assertEquals(List.of(main.resolve("com/example/app")), ProjectFiles.packageTrees(main.resolve("com/example/app")));
        assertEquals(List.of("", "com", "com.example", "com.example.app", "com.example.app.model", "com.example.util"),
                ProjectFiles.packages(main));
    }

    @Test
    void foldersMoveAndDeleteAsOneTransactionOfTextFiles() throws Exception {
        Path config = Files.createDirectories(project.resolve("config/dev"));
        Files.writeString(config.resolve("app.properties"), "port=8080\n");
        Files.writeString(project.resolve("config/readme.md"), "# Config\n");
        var move = ProjectFiles.movePlan(project, project.resolve("config"), project.resolve("settings"), "Renamed config");
        assertEquals(List.of(
                new Model.FileOperation(Model.FileOperationKind.MOVE, config.resolve("app.properties"), project.resolve("settings/dev/app.properties"), null),
                new Model.FileOperation(Model.FileOperationKind.MOVE, project.resolve("config/readme.md"), project.resolve("settings/readme.md"), null)),
                move.fileOperations());
        assertEquals("port=8080\n", move.before().sources().getFirst().text(), "the transaction can restore what it moved");
        var delete = ProjectFiles.deletePlan(project, project.resolve("config"), "Delete config");
        assertEquals(2, delete.fileOperations().size());
        assertTrue(delete.fileOperations().stream().allMatch(op -> op.kind() == Model.FileOperationKind.DELETE));
        assertTrue(move.valid() && delete.valid());
    }

    @Test
    void binaryFilesAreRecognisedAndEmptyFoldersPruned() throws Exception {
        Path images = Files.createDirectories(project.resolve("assets/images"));
        Files.write(images.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        Files.writeString(project.resolve("assets/notes.txt"), "text");
        assertFalse(ProjectFiles.allText(ProjectFiles.files(project.resolve("assets"))));
        assertTrue(ProjectFiles.allText(List.of(project.resolve("assets/notes.txt"))));
        assertThrows(java.io.IOException.class, () -> ProjectFiles.deletePlan(project, project.resolve("assets"), "x"));
        Files.delete(images.resolve("logo.png"));
        Files.delete(project.resolve("assets/notes.txt"));
        Files.createDirectories(project.resolve("assets/empty/deeper"));
        ProjectFiles.pruneTree(project.resolve("assets"));
        assertFalse(Files.exists(project.resolve("assets")), "every empty folder goes, the top one included");
    }
}
