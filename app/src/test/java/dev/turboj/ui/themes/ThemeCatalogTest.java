package dev.turboj.ui.themes;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.SwingUtilities;

class ThemeCatalogTest {
    @TempDir Path directory;

    private ThemeCatalog.Page page(ThemeCatalog catalog, String query, int page, String preferred)
            throws Exception {
        CompletableFuture<ThemeCatalog.Page> result = new CompletableFuture<>();
        catalog.request(query, page, preferred, result::complete);
        return result.get(20, TimeUnit.SECONDS);
    }

    @Test
    void thousandLocalMetadataFixturesHaveBoundedLazyPagesAndCache() throws Exception {
        String original = Theme.resourceJson("dark.json");
        for (int i = 0; i < 1000; i++)
            Files.writeString(
                    directory.resolve(String.format("fixture-%04d.json", i)),
                    original.replace("Modern Dark", String.format("Fixture %04d", i))
                            .replace("\"dark\"", "\"fixture_" + i + "\""));
        long started = System.nanoTime();
        try (var catalog = new ThemeCatalog(directory)) {
            CompletableFuture<ThemeCatalog.Page> first = new CompletableFuture<>();
            AtomicBoolean callbackOnEdt = new AtomicBoolean(true);
            SwingUtilities.invokeAndWait(
                    () ->
                            catalog.request(
                                    "",
                                    0,
                                    null,
                                    p -> {
                                        callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                                        first.complete(p);
                                    }));
            var p = first.get(20, TimeUnit.SECONDS);
            assertFalse(callbackOnEdt.get());
            assertEquals(1000 + Theme.bundledResources().size(), p.total());
            assertEquals(6, p.previews().size());
            assertEquals("Turbo Classic", p.previews().getFirst().entry().name());
            assertEquals(6, catalog.statistics().cachedPalettes());
            assertEquals(
                    1000 + Theme.bundledResources().size(), catalog.statistics().indexedEntries());
            for (int i = 1; i < 16; i++)
                assertEquals(6, page(catalog, "", i, null).previews().size());
            assertEquals(ThemeCatalog.CACHE_LIMIT, catalog.statistics().cachedPalettes());
            var last = page(catalog, "", 9999, null);
            assertEquals((p.total() - 1) / 6, last.page());
            assertEquals((p.total() - 1) % 6 + 1, last.previews().size());
            var search = page(catalog, "Fixture 09", 0, null);
            assertEquals(100, search.total());
            assertEquals(6, search.previews().size());
            var chosen =
                    page(
                            catalog,
                            "Fixture",
                            -1,
                            ThemeCatalog.localKey(directory.resolve("fixture-0999.json")));
            assertEquals(166, chosen.page());
            assertEquals(
                    "Fixture 0999", chosen.previews().get(chosen.selected() % 6).entry().name());
            System.out.printf(
                    "Theme catalog: 1000 local fixtures indexed, paged, filtered and LRU checked in"
                            + " %.2f ms%n",
                    (System.nanoTime() - started) / 1e6);
        }
    }

    @Test
    void coalescesRapidSearchAndDoesNotRetainAnUnboundedQueue() throws Exception {
        try (var catalog = new ThemeCatalog(directory)) {
            for (int i = 0; i < 1000; i++) {
                catalog.request("obsolete-" + i, 0, null, p -> {});
                assertTrue(catalog.statistics().pendingRequests() <= 1);
            }
            var latest = page(catalog, "Modern Light", 0, null);
            assertEquals("Modern Light", latest.query());
            assertEquals(1, latest.total());
            assertTrue(catalog.statistics().cachedPalettes() <= 24);
        }
    }

    @Test
    void invalidMetadataIsSkippedAndInvalidPalettesDoNotAbortOtherPreviews() throws Exception {
        Files.writeString(directory.resolve("bad.json"), "invalid");
        Files.writeString(
                directory.resolve("incomplete.json"),
                "{\"schemaVersion\":2,\"id\":\"incomplete\",\"name\":\"Broken palette\"}");
        try (var catalog = new ThemeCatalog(directory)) {
            var page = page(catalog, "Broken palette", 0, null);
            assertEquals(1, page.total());
            assertEquals(1, page.invalidFiles());
            assertNull(page.previews().getFirst().theme());
            assertNotNull(page.previews().getFirst().problem());
            Files.writeString(
                    directory.resolve("incomplete.json"),
                    Theme.resourceJson("light.json").replace("Modern Light", "Repaired palette"));
            catalog.refresh();
            assertNotNull(page(catalog, "Repaired palette", 0, null).previews().getFirst().theme());
        }
    }

    @Test
    void externalFileSelectionIsIndexedAndRegistryHasOnlyRealBundledPalettes() throws Exception {
        Path external = directory.resolve("outside.json");
        Files.writeString(
                external,
                Theme.resourceJson("light.json")
                        .replace("Modern Light", "Selected external theme"));
        try (var catalog = new ThemeCatalog(directory.resolve("themes"), external)) {
            var page = page(catalog, "", -1, ThemeCatalog.localKey(external));
            assertEquals(Theme.bundledResources().size() + 1, page.total());
            assertEquals(external, page.previews().get(page.selected() % 6).entry().localPath());
        }
        for (var entry : Theme.bundledResources().entrySet())
            assertEquals(entry.getKey(), Theme.parse(Theme.resourceJson(entry.getValue())).id());
        assertEquals(3, Theme.BUNDLED.length);
    }

    @Test
    void randomChoiceUsesFilteredCatalogAndPrefetchRemainsBounded() throws Exception {
        try (var catalog = new ThemeCatalog(directory)) {
            CompletableFuture<ThemeCatalog.WheelResult> result = new CompletableFuture<>();
            catalog.requestRandom("Modern Light", result::complete);
            var wheel = result.get(20, TimeUnit.SECONDS);
            assertNull(wheel.problem());
            assertEquals(1, wheel.total());
            assertEquals("Modern Light", wheel.target().entry().name());
            assertNotNull(wheel.target().theme());
            assertEquals(wheel.target().entry(), wheel.plan().target().entry());
            assertTrue(wheel.prepared().size() <= 7);
            CompletableFuture<ThemeCatalog.PreviewBatch> prefetch = new CompletableFuture<>();
            catalog.prefetch(
                    wheel.plan().frames().stream().map(ThemeWheelPlan.Frame::entry).toList(),
                    prefetch::complete);
            assertTrue(prefetch.get(20, TimeUnit.SECONDS).previews().size() <= 6);
            assertTrue(catalog.statistics().cachedPalettes() <= ThemeCatalog.CACHE_LIMIT);
            catalog.close();
            assertEquals(
                    -1, catalog.requestRandom("", p -> fail("Closed catalog delivered a wheel")));
        }
    }

    @Test
    void cancellingABrowserKeepsItsWarmIndexAndCacheAndDetectsNewLocalFiles() throws Exception {
        try (var catalog = new ThemeCatalog(directory)) {
            var first = page(catalog, "", 0, null);
            int scans = catalog.statistics().indexScans();
            int cached = catalog.statistics().cachedPalettes();
            catalog.cancelPending();
            assertEquals(first.total(), page(catalog, "", 0, null).total());
            assertEquals(scans, catalog.statistics().indexScans());
            assertEquals(cached, catalog.statistics().cachedPalettes());
            Files.writeString(
                    directory.resolve("new.json"),
                    Theme.resourceJson("light.json").replace("Modern Light", "New local theme"));
            Files.setLastModifiedTime(
                    directory,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));
            assertEquals(1, page(catalog, "New local theme", 0, null).total());
            assertTrue(catalog.statistics().indexScans() > scans);
        }
    }

    @Test
    void rowWindowLoadsOnlySixPalettesAndClampsToTheLastTwoRows() throws Exception {
        try (var catalog = new ThemeCatalog(directory)) {
            CompletableFuture<ThemeCatalog.Page> first = new CompletableFuture<>();
            catalog.requestWindow("", 1, null, first::complete);
            var row = first.get(20, TimeUnit.SECONDS);
            assertEquals(3, row.firstIndex());
            assertEquals(6, row.previews().size());
            assertEquals(6, catalog.statistics().cachedPalettes());
            CompletableFuture<ThemeCatalog.Page> last = new CompletableFuture<>();
            catalog.requestWindow("", 9999, null, last::complete);
            var end = last.get(20, TimeUnit.SECONDS);
            assertEquals(Math.max(0, (end.total() + 2) / 3 - 2) * 3, end.firstIndex());
            assertEquals(end.total() - end.firstIndex(), end.previews().size());
            assertTrue(end.previews().size() <= 6);
        }
    }

    @Test
    void rejectsOversizedPaletteAndSupportsSharedNameAndIdContract() throws Exception {
        Path large = directory.resolve("large.json");
        Files.writeString(large, " ".repeat(65537));
        assertThrows(java.io.IOException.class, () -> Theme.load(large));
        String longName = "A".repeat(120);
        Theme t =
                Theme.parse(
                        Theme.resourceJson("dark.json")
                                .replace("Modern Dark", longName)
                                .replace("\"dark\"", "\"custom_dark\""));
        assertEquals(longName, t.name());
        assertEquals("custom_dark", t.id());
    }
}
