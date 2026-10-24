package dev.turboj.ai;

import static java.lang.foreign.ValueLayout.*;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import javax.swing.SwingUtilities;

/**
 * Native SecItem API through the stable Java FFM API. There is no shell, subprocess, plaintext
 * fallback, or credential in a command line. The native libraries are loaded only on first use.
 */
public final class MacKeychainCredentialStore implements CredentialStore {
    private static final int NOT_FOUND = -25300;
    private static final int DUPLICATE = -25299;
    private static final int MAX_SECRET_BYTES = 16_384;
    private final String service;

    public MacKeychainCredentialStore() {
        // Persisted service identity predates the Java namespace migration.
        this("com.turboj.ai.credentials");
    }

    /** Package-private namespace supports isolated, disposable integration smoke tests. */
    MacKeychainCredentialStore(String service) {
        if (!service.matches("com\\.turboj\\.ai\\.[A-Za-z0-9.-]{1,120}"))
            throw new IllegalArgumentException("Invalid Keychain service namespace.");
        this.service = service;
    }

    @Override
    public String read(String endpoint) throws IOException {
        requireBackground();
        String account = CredentialStore.normalizeEndpoint(endpoint);
        Native api = nativeApi();
        try (Arena arena = Arena.ofConfined();
                Native.Dictionary query = api.query(service, account)) {
            query.put(api.constant("kSecReturnData"), api.cfConstant("kCFBooleanTrue"));
            query.put(api.constant("kSecMatchLimit"), api.constant("kSecMatchLimitOne"));
            MemorySegment result = arena.allocate(ADDRESS);
            int status = api.status(api.copy, query.address, result);
            if (status == NOT_FOUND) return "";
            api.check(status);
            MemorySegment data = result.get(ADDRESS, 0);
            if (data.equals(MemorySegment.NULL))
                throw new IOException("Keychain returned no credential data.");
            try {
                long length = (long) api.call(api.dataLength, data);
                if (length < 1 || length > MAX_SECRET_BYTES)
                    throw new IOException("Stored credential has an invalid size.");
                MemorySegment pointer = (MemorySegment) api.call(api.dataBytes, data);
                byte[] bytes = pointer.reinterpret(length).toArray(JAVA_BYTE);
                try {
                    return new String(bytes, StandardCharsets.UTF_8);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            } finally {
                api.release(data);
            }
        }
    }

    @Override
    public void write(String endpoint, char[] secret) throws IOException {
        requireBackground();
        String account = CredentialStore.normalizeEndpoint(endpoint);
        if (secret == null || secret.length == 0 || secret.length > MAX_SECRET_BYTES)
            throw new IllegalArgumentException(
                    "Enter a nonempty API credential of at most 16 KiB.");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        try {
            if (bytes.length > MAX_SECRET_BYTES)
                throw new IllegalArgumentException("API credential exceeds 16 KiB.");
            Native api = nativeApi();
            try (Arena arena = Arena.ofConfined();
                    Native.Dictionary query = api.query(service, account);
                    Native.Dictionary update = api.dictionary()) {
                MemorySegment temporary = arena.allocateFrom(JAVA_BYTE, bytes);
                MemorySegment data =
                        (MemorySegment)
                                api.call(
                                        api.dataCreate,
                                        MemorySegment.NULL,
                                        temporary,
                                        (long) bytes.length);
                temporary.fill((byte) 0);
                if (data.equals(MemorySegment.NULL))
                    throw new IOException("Unable to allocate Keychain data.");
                try {
                    update.put(api.constant("kSecValueData"), data);
                    int status = api.status(api.update, query.address, update.address);
                    if (status == NOT_FOUND) {
                        query.put(api.constant("kSecValueData"), data);
                        status = api.status(api.add, query.address, MemorySegment.NULL);
                        // Another writer may have inserted between the update and add.
                        if (status == DUPLICATE) {
                            try (Native.Dictionary retry = api.query(service, account)) {
                                status = api.status(api.update, retry.address, update.address);
                            }
                        }
                    }
                    api.check(status);
                } finally {
                    api.release(data);
                }
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void delete(String endpoint) throws IOException {
        requireBackground();
        String account = CredentialStore.normalizeEndpoint(endpoint);
        Native api = nativeApi();
        try (Native.Dictionary query = api.query(service, account)) {
            int status = api.status(api.delete, query.address);
            if (status != NOT_FOUND) api.check(status);
        }
    }

    private static void requireBackground() throws IOException {
        if (SwingUtilities.isEventDispatchThread())
            throw new IOException("Keychain operations must run outside the editor event thread.");
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"))
            throw new IOException(
                    "Cloud credentials require macOS Keychain; no plaintext fallback is"
                        + " available.");
    }

    private static Native nativeApi() throws IOException {
        try {
            return Holder.INSTANCE;
        } catch (LinkageError | RuntimeException e) {
            throw new IOException(
                    "macOS Keychain is unavailable. Start TurboJ with"
                        + " --enable-native-access=ALL-UNNAMED.");
        }
    }

    private static final class Holder {
        private static final Native INSTANCE = new Native();
    }

    private static final class Native {
        private final Linker linker = Linker.nativeLinker();
        private final SymbolLookup security =
                SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/Security.framework/Security", Arena.global());
        private final SymbolLookup foundation =
                SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                        Arena.global());
        private final MethodHandle copy =
                function(
                        security,
                        "SecItemCopyMatching",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle add =
                function(security, "SecItemAdd", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle update =
                function(
                        security,
                        "SecItemUpdate",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle delete =
                function(security, "SecItemDelete", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private final MethodHandle dictionaryCreate =
                function(
                        foundation,
                        "CFDictionaryCreateMutable",
                        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        private final MethodHandle dictionarySet =
                function(
                        foundation,
                        "CFDictionarySetValue",
                        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        private final MethodHandle stringCreate =
                function(
                        foundation,
                        "CFStringCreateWithCString",
                        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        private final MethodHandle dataCreate =
                function(
                        foundation,
                        "CFDataCreate",
                        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        private final MethodHandle dataLength =
                function(foundation, "CFDataGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        private final MethodHandle dataBytes =
                function(foundation, "CFDataGetBytePtr", FunctionDescriptor.of(ADDRESS, ADDRESS));
        private final MethodHandle release =
                function(foundation, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));

        private MethodHandle function(
                SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(library.find(name).orElseThrow(), descriptor);
        }

        private MemorySegment constant(String name) {
            return security.find(name)
                    .orElseThrow()
                    .reinterpret(ADDRESS.byteSize())
                    .get(ADDRESS, 0);
        }

        private MemorySegment cfConstant(String name) {
            return foundation
                    .find(name)
                    .orElseThrow()
                    .reinterpret(ADDRESS.byteSize())
                    .get(ADDRESS, 0);
        }

        private Object call(MethodHandle method, Object... args) throws IOException {
            try {
                return method.invokeWithArguments(args);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                throw new IOException("The native Keychain operation failed.");
            }
        }

        private int status(MethodHandle method, Object... args) throws IOException {
            return (int) call(method, args);
        }

        private void check(int status) throws IOException {
            if (status != 0)
                throw new IOException(
                        "Keychain access failed (OSStatus "
                                + status
                                + "). Unlock or allow access in macOS Keychain.");
        }

        private void release(MemorySegment reference) throws IOException {
            if (!reference.equals(MemorySegment.NULL)) call(release, reference);
        }

        private Dictionary dictionary() throws IOException {
            MemorySegment ref =
                    (MemorySegment)
                            call(
                                    dictionaryCreate,
                                    MemorySegment.NULL,
                                    0L,
                                    foundation.find("kCFTypeDictionaryKeyCallBacks").orElseThrow(),
                                    foundation
                                            .find("kCFTypeDictionaryValueCallBacks")
                                            .orElseThrow());
            if (ref.equals(MemorySegment.NULL))
                throw new IOException("Unable to allocate a Keychain query.");
            return new Dictionary(ref);
        }

        private Dictionary query(String service, String account) throws IOException {
            Dictionary query = dictionary();
            try {
                query.put(constant("kSecClass"), constant("kSecClassGenericPassword"));
                query.putString("kSecAttrService", service);
                query.putString("kSecAttrAccount", account);
                return query;
            } catch (IOException | RuntimeException failure) {
                query.close();
                throw failure;
            }
        }

        private final class Dictionary implements AutoCloseable {
            private final MemorySegment address;

            private Dictionary(MemorySegment address) {
                this.address = address;
            }

            private void put(MemorySegment key, MemorySegment value) throws IOException {
                call(dictionarySet, address, key, value);
            }

            private void putString(String key, String value) throws IOException {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment string =
                            (MemorySegment)
                                    call(
                                            stringCreate,
                                            MemorySegment.NULL,
                                            arena.allocateFrom(value),
                                            0x08000100);
                    if (string.equals(MemorySegment.NULL))
                        throw new IOException("Unable to allocate a Keychain attribute.");
                    try {
                        put(constant(key), string);
                    } finally {
                        release(string);
                    }
                }
            }

            @Override
            public void close() throws IOException {
                release(address);
            }
        }
    }
}
