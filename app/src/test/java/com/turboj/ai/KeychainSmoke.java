package com.turboj.ai;

import java.util.Arrays;
import java.util.UUID;

/**
 * Explicit macOS acceptance test, never run by the ordinary unit suite. Uses no real credential.
 */
public final class KeychainSmoke {
    public static void main(String[] args) throws Exception {
        String namespace = "com.turboj.ai.test." + UUID.randomUUID();
        CredentialStore store = new MacKeychainCredentialStore(namespace);
        String endpoint = "https://keychain-test.invalid/v1";
        char[] first = ("test-only-" + UUID.randomUUID()).toCharArray();
        char[] second = ("updated-test-only-" + UUID.randomUUID()).toCharArray();
        try {
            if (!store.read(endpoint).isEmpty())
                throw new AssertionError("Test namespace unexpectedly populated");
            store.write(endpoint, first);
            if (!Arrays.equals(first, store.read(endpoint).toCharArray()))
                throw new AssertionError("Keychain round trip failed");
            if (!store.read("https://keychain-test.invalid/v2").isEmpty())
                throw new AssertionError("Endpoint credentials leaked");
            store.write(endpoint, second);
            if (!Arrays.equals(second, store.read(endpoint).toCharArray()))
                throw new AssertionError("Keychain update failed");
            store.delete(endpoint);
            if (!store.read(endpoint).isEmpty())
                throw new AssertionError("Keychain deletion failed");
            store.delete(endpoint);
            System.out.println(
                    "PASS native Keychain create/read/update/delete, endpoint separation and"
                        + " missing-entry handling; disposable test namespace removed.");
        } finally {
            Arrays.fill(first, '\0');
            Arrays.fill(second, '\0');
            store.delete(endpoint);
        }
    }
}
