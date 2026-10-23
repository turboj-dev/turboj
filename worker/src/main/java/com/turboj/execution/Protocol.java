package com.turboj.execution;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Private inherited pipes. No listening socket, serialization, or externally discoverable endpoint.
 */
final class Protocol {
    static final int VERSION = 2;
    static final int MAGIC = 0x544A5731;
    static final byte READY = 1,
            RUN = 2,
            INPUT = 3,
            EOF = 4,
            OUTPUT = 5,
            RESULT = 6,
            PING = 7,
            PONG = 8,
            INPUT_STATE = 9;
    static final int MAX_FRAME = 64 * 1024 * 1024;
    static final int MAX_STRING = 1024 * 1024;
    static final int MAX_CLASSES = 8192;
    static final int MAX_INPUT = 64 * 1024;

    record Frame(byte type, DataInputStream data) {}

    @FunctionalInterface
    interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    static void write(DataOutputStream out, byte type, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            body.write(data);
        }
        if (bytes.size() > MAX_FRAME) throw new IOException("Worker frame exceeds 64 MiB");
        synchronized (out) {
            out.writeInt(MAGIC);
            out.writeByte(type);
            out.writeInt(bytes.size());
            bytes.writeTo(out);
            out.flush();
        }
    }

    static Frame read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid worker protocol magic");
        byte type = in.readByte();
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) throw new IOException("Invalid worker frame length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete worker frame");
        return new Frame(type, new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING) throw new IOException("Worker string too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static String string(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING || length > in.available())
            throw new IOException("Invalid worker string length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    static void exhausted(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IOException("Unexpected trailing worker frame data");
    }
}
