package dev.turboj.mavenj.workspace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PomEditsTest {
    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <!-- Keep my comment 🧪 -->
              <properties><library.version>1.0</library.version></properties>
              <dependencies>
                <!-- Keep declaration comment -->
                <dependency><groupId>example</groupId><artifactId>lib</artifactId><version>${library.version}</version></dependency>
              </dependencies>
            </project>
            """;

    @Test
    void versionUpdatesPropertyWithoutReplacingExpressionOrComments() {
        var plan = PomEdits.updateDependencyVersion(POM, "example", "lib", "2.0");
        assertEquals(POM.replace("<library.version>1.0</library.version>",
                "<library.version>2.0</library.version>"), plan.result());
        assertEquals(1, plan.edits().size());
        assertTrue(plan.description().contains("every use"));
        assertEquals(plan.result(), plan.applyTo(POM));
        assertThrows(IllegalStateException.class, () -> plan.applyTo(POM + " "));
    }

    @Test
    void inheritedPropertiesAreNotHardcoded() {
        String xml = POM.replace("<properties><library.version>1.0</library.version></properties>",
                "");
        assertThrows(IllegalArgumentException.class, () -> PomEdits.updateDependencyVersion(xml,
                "example", "lib", "2.0"));
    }

    @Test
    void addsAndRemovesOnlyRequestedDirectDependency() {
        var add = PomEdits.addDependency(POM, "org.junit.jupiter", "junit-jupiter", "5.13.4",
                "test");
        assertTrue(add.result().contains("<scope>test</scope>"));
        assertTrue(add.result().contains("<!-- Keep my comment 🧪 -->"));
        var remove = PomEdits.removeDependency(add.result(), "org.junit.jupiter", "junit-jupiter");
        assertEquals(POM, remove.result());
        assertThrows(IllegalArgumentException.class, () -> PomEdits.addDependency(POM, "example",
                "lib", "2", "compile"));
    }

    @Test
    void supportsPrefixedNamespacesSelfClosingContainersAndCrLf() {
        String xml = "<m:project xmlns:m=\"http://maven.apache.org/POM/4.0.0\">\r\n\t<m:dependencies/>\r\n</m:project>\r\n";
        var result = PomEdits.addDependency(xml, "a", "b", "1", "compile").result();
        assertTrue(result.contains("\t\t<m:dependency>\r\n"));
        assertTrue(result.contains("<m:groupId>a</m:groupId>"));
        assertFalse(result.replace("\r\n", "").contains("\n"));
    }

    @Test
    void addsModulesWithoutReorderingAnything() {
        var result = PomEdits.addModule(POM, "new-module").result();
        assertTrue(result.startsWith(POM.substring(0, POM.lastIndexOf("</project>"))));
        assertTrue(result.contains("<module>new-module</module>"));
        assertThrows(IllegalArgumentException.class, () -> PomEdits.addModule(result,
                "new-module"));
    }

    @Test
    void rejectsDoctypeAndMalformedXmlAndAmbiguousCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> PomEdits
                .addModule("<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><project>&x;</project>",
                "module"));
        assertThrows(IllegalArgumentException.class, () -> PomEdits
                .addModule("<project><broken></project>", "module"));
        String duplicate = POM.replace("</dependencies>",
                "<dependency><groupId>example</groupId><artifactId>lib</artifactId><classifier>tests</classifier></dependency></dependencies>");
        assertThrows(IllegalArgumentException.class, () -> PomEdits.removeDependency(duplicate,
                "example", "lib"));
    }

    @Test
    void ignoresFakeTagsInCommentsAndPreservesSurrogateOffsets() {
        String xml = POM.replace("<!-- Keep my comment 🧪 -->",
                "<!-- 🧪 <dependencies><dependency/> -->");
        var plan = PomEdits.removeDependency(xml, "example", "lib");
        assertTrue(plan.result().contains("<!-- 🧪 <dependencies><dependency/> -->"));
        assertEquals(plan.result(), xml.substring(0, plan.edits().getFirst().start()) + plan.edits()
                .getFirst().text() + xml.substring(plan.edits().getFirst().end()));
    }
}
