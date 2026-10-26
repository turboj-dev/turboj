package dev.turboj.refactorj.jdt;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** JUnit Jupiter API types offered before the library is on the build path, as Eclipse's JUnit quick fix does. */
final class JupiterApi {
    static final String PACKAGE = "org.junit.jupiter.api";
    static final String PROBE = PACKAGE + ".Test";
    static final Set<String> ANNOTATIONS = Set.of("Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll",
            "DisplayName", "Disabled", "Nested", "Tag", "Timeout", "RepeatedTest", "TestFactory");
    /** TurboJ provides only Jupiter, so an unresolved JUnit 4 annotation import moves to its Jupiter equivalent. */
    static final String JUNIT4_PACKAGE = "org.junit";
    static final String JUNIT4_PROBE = JUNIT4_PACKAGE + ".Test";
    static final Map<String, String> JUNIT4 = Map.of("Test", "Test", "Before", "BeforeEach", "After", "AfterEach",
            "BeforeClass", "BeforeAll", "AfterClass", "AfterAll", "Ignore", "Disabled");
    static final String ASSERTIONS = PACKAGE + ".Assertions";
    /** Offered, with the library, before JUnit is on the build path. */
    static final Set<String> ASSERTION_METHODS = Set.of("assertEquals", "assertNotEquals", "assertTrue", "assertFalse",
            "assertNull", "assertNotNull", "assertSame", "assertNotSame", "assertThrows", "assertDoesNotThrow", "assertAll",
            "assertArrayEquals", "assertIterableEquals", "assertLinesMatch", "assertInstanceOf", "assertTimeout", "fail");
    /** Eclipse's default content-assist favorites: static members completed, and imported, by their name alone. */
    static final List<String> FAVORITES = List.of(ASSERTIONS, PACKAGE + ".Assumptions", "org.junit.Assert", "org.junit.Assume",
            "org.mockito.Mockito", "org.mockito.ArgumentMatchers");

    /** Favorites are test libraries, so they are offered only in test sources. */
    static boolean testSource(java.nio.file.Path file, String source) {
        return file.getFileName().toString().endsWith("Test.java") || file.getFileName().toString().endsWith("Tests.java")
                || source.contains("@Test") || source.contains("org.junit");
    }
    private JupiterApi() {}
}
