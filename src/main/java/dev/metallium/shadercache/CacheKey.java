/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.shadercache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 content key builder. Every value is length- or type-prefixed, so {@code add("ab").add("c")} and
 * {@code add("a").add("bc")} give different keys.
 */
public final class CacheKey {
    private final MessageDigest digest;

    private CacheKey(final String namespace) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        add(namespace);
    }

    public static CacheKey of(final String namespace) {
        return new CacheKey(namespace);
    }

    public CacheKey add(final String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        add(bytes.length);
        this.digest.update(bytes);
        return this;
    }

    public CacheKey add(final int value) {
        this.digest.update((byte) 'i');
        this.digest.update((byte) (value >>> 24));
        this.digest.update((byte) (value >>> 16));
        this.digest.update((byte) (value >>> 8));
        this.digest.update((byte) value);
        return this;
    }

    public CacheKey add(final boolean value) {
        this.digest.update((byte) (value ? 'T' : 'F'));
        return this;
    }

    /** Adds the remaining bytes of {@code buffer} without changing its position. */
    public CacheKey add(final ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        add(view.remaining());
        this.digest.update(view);
        return this;
    }

    public CacheKey add(final int[] values) {
        add(values.length);
        for (int value : values) {
            add(value);
        }
        return this;
    }

    /** 64 lowercase hex characters. The builder must not be used afterwards. */
    public String hex() {
        return HexFormat.of().formatHex(this.digest.digest());
    }
}
