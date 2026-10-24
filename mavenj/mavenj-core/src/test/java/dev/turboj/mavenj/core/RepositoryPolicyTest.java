package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.MavenModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryPolicyTest {
    @TempDir
    Path temp;

    @Test
    void repositoryAccessRequiresPermissionAndUsesMirrorCredentialsWithoutLeaking() throws Exception {
        String dependencyPom = "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></project>";
        var jar = new ByteArrayOutputStream();

        try (var out = new JarOutputStream(jar)) {
            out.putNextEntry(new JarEntry("META-INF/"));
            out.closeEntry();
        }
        Map<String, byte[]> artifacts = new HashMap<> ();
        artifacts.put("/repository/fixture/lib/1/lib-1.pom", dependencyPom.getBytes());
        artifacts.put("/repository/fixture/lib/1/lib-1.jar", jar.toByteArray());
        artifacts.put("/repository/fixture/lib/1/lib-1-sources.jar", jar.toByteArray());
        for (var entry : new ArrayList<> (artifacts.entrySet()))
            artifacts.put(entry.getKey() + ".sha1", HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-1").digest(entry.getValue())).getBytes());
        AtomicInteger requests = new AtomicInteger(), authorized = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress
                .getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("builder:super-secret-token".getBytes());

            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=fixture");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            authorized.incrementAndGet();
            byte[] body = artifacts.get(exchange.getRequestURI().getPath());

            if (body == null)
                exchange.sendResponseHeaders(404, -1);
            else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/repository";
            Path settings = temp.resolve("settings.xml");
            Files.writeString(settings,
                    "<settings><mirrors><mirror><id>private-mirror</id><mirrorOf>*</mirrorOf><url>"
                    + url
                    + "</url></mirror></mirrors><servers><server><id>private-mirror</id><username>builder</username><password>super-secret-token</password></server></servers></settings>");
            Path pom = temp.resolve("pom.xml");
            Files.writeString(pom,
                    "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release><deploy.password>dont-publish-me</deploy.password></properties><dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies></project>");
            try (var engine = new DefaultMavenJ()) {
                var denied = new ImportRequest(pom, settings, null, temp.resolve("denied-cache"),
                        Set.of(), Set.of(), Map.of(), false, Set.of(), false, null, Map.of());
                Workspace blocked = engine.open(denied);
                assertFalse(blocked.resolved());
                assertEquals(0, requests.get());
                assertFalse(blocked.toString().contains("super-secret-token"));
                assertFalse(blocked.toString().contains("dont-publish-me"));
                // The desktop opt-in accepts endpoints from user settings, without copying credentials.
                var desktop = MavenImportDefaults.withUserRepositories(new ImportRequest(pom, settings, null,
                        temp.resolve("desktop-cache"), Set.of(), Set.of(), Map.of(), false, Set.of(), false, null, Map.of()));
                assertTrue(desktop.allowedRepositoryUrls().contains(url));
                assertTrue(desktop.allowedRepositoryUrls().contains(MavenImportDefaults.CENTRAL));
                assertFalse(desktop.toString().contains("super-secret-token"));
                assertTrue(engine.open(desktop).resolved());
                var allowed = new ImportRequest(pom, settings, null, temp.resolve("allowed-cache"),
                        Set.of(), Set.of(), Map.of(), false, Set.of(url), false, null, Map.of());
                Workspace resolved = engine.open(allowed);
                assertTrue(resolved.resolved(), resolved.toString());
                assertTrue(authorized.get() > 0);
                assertFalse(resolved.toString().contains("super-secret-token"));
                assertFalse(resolved.toString().contains("dont-publish-me"));
                int before = requests.get();
                assertTrue(engine.open(allowed).cacheHit());
                assertEquals(before, requests.get());
                var attachment = engine.attachment(allowed, "fixture:app:1",
                        new Coordinates("fixture", "lib", "1", "jar", ""), "sources", () -> false);
                assertNotNull(attachment.file(), attachment.diagnostics().toString());
                assertTrue(requests.get() > before);
                int offlineBefore = requests.get();
                var offline = new ImportRequest(pom, settings, null, temp.resolve("allowed-cache"),
                        Set.of(), Set.of(), Map.of(), true, Set.of(), false, null, Map.of());
                assertTrue(engine.open(offline).resolved());
                assertEquals(offlineBefore, requests.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void desktopDefaultsHonorSettingsOfflineAndCannotGrantProjectRepositoryAccess() throws Exception {
        Path settings = temp.resolve("user-settings.xml");
        Path global = temp.resolve("global-settings.xml");
        Path repository = temp.resolve("local-repository");
        Files.writeString(settings, "<settings><offline>true</offline><localRepository>" + repository + "</localRepository></settings>");
        Files.writeString(global, "<settings/>");
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <repositories><repository><id>project-controlled</id><url>https://project.invalid/repository</url></repository></repositories></project>
                """);
        Files.createDirectory(project.resolve(".mvn"));
        Files.writeString(project.resolve(".mvn/maven.config"), "-s\nproject-settings.xml\n");
        Files.writeString(project.resolve("project-settings.xml"), "<settings><mirrors><mirror><id>untrusted</id><mirrorOf>*</mirrorOf><url>https://project.invalid/mirror</url></mirror></mirrors></settings>");
        ImportRequest request = MavenImportDefaults.withUserRepositories(new ImportRequest(project, settings, global,
                null, Set.of(), Set.of(), Map.of(), false, Set.of(), false, null, Map.of()));
        assertEquals(Set.of(MavenImportDefaults.CENTRAL), request.allowedRepositoryUrls());
        assertFalse(MavenImportDefaults.online(project).offline());
        assertTrue(ImportRequest.offline(project).allowedRepositoryUrls().isEmpty());
        try (var engine = new DefaultMavenJ()) {
            Workspace model = engine.open(request);
            assertTrue(model.resolved(), model.diagnostics().toString());
            assertTrue(model.offline());
            assertEquals(repository, model.localRepository());
        }
    }

    @Test
    void authorizedRepositoryCannotRedirectToAnUnauthorizedEndpoint() throws Exception {
        AtomicInteger forbiddenRequests = new AtomicInteger();
        HttpServer forbidden = HttpServer.create(new InetSocketAddress(InetAddress
                .getLoopbackAddress(), 0), 0);
        forbidden.createContext("/", e -> {
            forbiddenRequests.incrementAndGet();
            e.sendResponseHeaders(404, -1);
            e.close();
        });
        forbidden.start();
        HttpServer allowed = HttpServer.create(new InetSocketAddress(InetAddress
                .getLoopbackAddress(), 0), 0);
        allowed.createContext("/", e -> {
            e.getResponseHeaders().add("Location", "http://localhost:" + forbidden.getAddress()
                    .getPort() + e.getRequestURI());
            e.sendResponseHeaders(302, -1);
            e.close();
        });
        allowed.start();
        try {
            String url = "http://localhost:" + allowed.getAddress().getPort();
            Path settings = temp.resolve("settings.xml");
            Files.writeString(settings,
                    "<settings><mirrors><mirror><id>redirect</id><mirrorOf>*</mirrorOf><url>" + url
                    + "</url></mirror></mirrors></settings>");
            Path pom = temp.resolve("pom.xml");
            Files.writeString(pom,
                    "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version><dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies></project>");
            try (var engine = new DefaultMavenJ()) {
                var request = new ImportRequest(pom, settings, null, temp.resolve("cache"), Set
                        .of(), Set.of(), Map.of(), false, Set.of(url), false, null, Map.of());
                assertFalse(engine.open(request).resolved());
                assertEquals(0, forbiddenRequests.get());
            }
        } finally {
            allowed.stop(0);
            forbidden.stop(0);
        }
    }
}
