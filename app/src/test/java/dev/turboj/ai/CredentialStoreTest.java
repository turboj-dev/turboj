package dev.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

class CredentialStoreTest {
    @Test
    void identityUsesHttpsOriginAndExactNormalizedPath() {
        assertEquals(
                "https://api.openai.com/v1",
                CredentialStore.normalizeEndpoint(" HTTPS://API.OPENAI.COM:443/v1/ "));
        assertEquals(
                "https://host.test:8443/v2",
                CredentialStore.normalizeEndpoint("https://host.test:8443/v2"));
        assertNotEquals(
                CredentialStore.normalizeEndpoint("https://host.test/v1"),
                CredentialStore.normalizeEndpoint("https://host.test/v2"));
        assertNotEquals(
                CredentialStore.normalizeEndpoint("https://host.test/v1"),
                CredentialStore.normalizeEndpoint("https://other.test/v1"));
        assertNotEquals(
                CredentialStore.normalizeEndpoint("https://host.test/v1%2Fextra"),
                CredentialStore.normalizeEndpoint("https://host.test/v1/extra"));
    }

    @Test
    void credentialIdentityRejectsUnsafeEndpointsWithoutNativeCalls() {
        for (String endpoint :
                new String[] {
                    "http://host.test",
                    "https://user:secret@host.test",
                    "https://host.test?key=a",
                    "https://host.test#fragment",
                    "https://host.test:99999",
                    "file:///tmp/key"
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CredentialStore.normalizeEndpoint(endpoint));
    }

    @Test
    void keychainOperationsRejectTheEdtBeforeAnyNativeAccess() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        new MacKeychainCredentialStore().read("https://api.openai.com/v1");
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
        assertInstanceOf(IOException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("event thread"));
    }
}
