package dev.turboj.ai;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Secrets are addressed by the exact normalized cloud endpoint, never by model or project. */
public interface CredentialStore {
    /** Returns an empty string if no credential is stored. Call only on a background thread. */
    String read(String endpoint) throws IOException;

    /** The caller retains ownership of its character array and must clear it after use. */
    void write(String endpoint, char[] secret) throws IOException;

    void delete(String endpoint) throws IOException;

    static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048)
            throw new IllegalArgumentException("A valid HTTPS cloud endpoint is required.");
        try {
            URI uri = URI.create(endpoint.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535)
                throw new IllegalArgumentException(
                        "Use an HTTPS endpoint without credentials, query or fragment.");
            String path = uri.getRawPath();
            if (path == null) path = "";
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new URI(
                                    "https",
                                    null,
                                    uri.getHost().toLowerCase(Locale.ROOT),
                                    uri.getPort() == 443 ? -1 : uri.getPort(),
                                    null,
                                    null,
                                    null)
                            .toASCIIString()
                    + path;
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new IllegalArgumentException("A valid HTTPS cloud endpoint is required.");
        }
    }
}
