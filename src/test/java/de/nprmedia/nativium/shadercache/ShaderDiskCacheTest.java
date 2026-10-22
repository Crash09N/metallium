/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.shadercache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ShaderDiskCacheTest {
    private static final long MB = 1024 * 1024;

    private static String key(final String content) {
        return CacheKey.of("test").add(content).hex();
    }

    @Test
    void keysAreStableAndPrefixFree() {
        assertEquals(CacheKey.of("ns").add("abc").add(1).hex(), CacheKey.of("ns").add("abc").add(1).hex());
        assertNotEquals(CacheKey.of("ns").add("ab").add("c").hex(), CacheKey.of("ns").add("a").add("bc").hex());
        assertNotEquals(CacheKey.of("a").add("x").hex(), CacheKey.of("b").add("x").hex());
        assertNotEquals(CacheKey.of("ns").add(true).hex(), CacheKey.of("ns").add(false).hex());
        assertNotEquals(CacheKey.of("ns").add(new int[] {1, 2}).hex(), CacheKey.of("ns").add(new int[] {1}).add(2).hex());
        assertEquals(64, CacheKey.of("ns").hex().length());
    }

    @Test
    void byteBufferKeyDoesNotConsumeTheBuffer() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        buffer.position(1);
        String first = CacheKey.of("ns").add(buffer).hex();
        assertEquals(1, buffer.position());
        assertEquals(first, CacheKey.of("ns").add(ByteBuffer.wrap(new byte[] {2, 3, 4})).hex());
    }

    @Test
    void roundTripAndMiss(@TempDir final Path root) {
        ShaderDiskCache cache = ShaderDiskCache.open(root, "env-a", 64 * MB);
        assertNotNull(cache);
        assertNull(cache.get("msl", key("missing")));
        byte[] value = "kernel void k() {}".getBytes(StandardCharsets.UTF_8);
        cache.put("msl", key("k"), value);
        assertArrayEquals(value, cache.get("msl", key("k")));
        assertTrue(cache.sizeBytes() > value.length);

        // A reopened cache (next session) sees the entry.
        ShaderDiskCache reopened = ShaderDiskCache.open(root, "env-a", 64 * MB);
        assertNotNull(reopened);
        assertArrayEquals(value, reopened.get("msl", key("k")));
    }

    @Test
    void corruptEntriesAreDeletedAndMiss(@TempDir final Path root) throws IOException {
        ShaderDiskCache cache = ShaderDiskCache.open(root, "env-a", 64 * MB);
        String k = key("k");
        cache.put("msl", k, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Path file = cache.entryPath("msl", k);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x55; // flip payload bits: CRC mismatch
        Files.write(file, bytes);
        assertNull(cache.get("msl", k));
        assertFalse(Files.exists(file));
        assertEquals(1, cache.corruptEntries.sum());

        cache.put("msl", k, new byte[] {9});
        Files.write(file, new byte[] {1, 2, 3}); // truncated
        assertNull(cache.get("msl", k));

        cache.put("msl", k, new byte[] {9});
        byte[] longer = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(longer, longer.length + 4)); // trailing garbage: length mismatch
        assertNull(cache.get("msl", k));
    }

    @Test
    void encodingRejectsOtherFormats() {
        byte[] encoded = ShaderDiskCache.encode(new byte[] {42});
        assertArrayEquals(new byte[] {42}, ShaderDiskCache.decode(encoded));
        byte[] wrongMagic = encoded.clone();
        wrongMagic[0] ^= 1;
        assertNull(ShaderDiskCache.decode(wrongMagic));
        byte[] wrongFormat = encoded.clone();
        wrongFormat[7] ^= 1;
        assertNull(ShaderDiskCache.decode(wrongFormat));
        assertArrayEquals(new byte[0], ShaderDiskCache.decode(ShaderDiskCache.encode(new byte[0])));
    }

    @Test
    void changedEnvironmentInvalidatesEverything(@TempDir final Path root) throws IOException {
        ShaderDiskCache first = ShaderDiskCache.open(root, "nativium=1\ngpu=Apple M3 Max", 64 * MB);
        first.put("msl", key("k"), new byte[] {1});
        Path oldDirectory = first.directory();

        ShaderDiskCache second = ShaderDiskCache.open(root, "nativium=2\ngpu=Apple M3 Max", 64 * MB);
        assertNotEquals(oldDirectory, second.directory());
        assertNull(second.get("msl", key("k")));
        assertFalse(Files.exists(oldDirectory), "stale environment directory is deleted");
        assertTrue(Files.readString(second.directory().resolve(ShaderDiskCache.ENV_FILE)).contains("nativium=2"));

        try (Stream<Path> dirs = Files.list(root)) {
            assertEquals(1, dirs.count());
        }
    }

    @Test
    void sizeCapEvictsLeastRecentlyUsed(@TempDir final Path root) throws IOException {
        long cap = 16 * 1024;
        ShaderDiskCache cache = ShaderDiskCache.open(root, "env-a", cap);
        byte[] value = new byte[4000];
        long time = System.currentTimeMillis() - 100_000;
        for (int i = 0; i < 3; i++) {
            cache.put("msl", key("e" + i), value);
            Files.setLastModifiedTime(cache.entryPath("msl", key("e" + i)), FileTime.fromMillis(time + i * 1000L));
        }
        // e0 is the oldest; reading it makes it the most recently used.
        assertNotNull(cache.get("msl", key("e0")));
        cache.put("msl", key("e3"), value);
        cache.put("msl", key("e4"), value); // > 16 KiB in total: prune down to 3/4 of the cap

        assertTrue(cache.sizeBytes() <= cap, "size " + cache.sizeBytes());
        assertTrue(cache.evictedEntries.sum() > 0);
        assertNull(cache.get("msl", key("e1")), "least recently used entry evicted first");
        assertNotNull(cache.get("msl", key("e0")));
        assertNotNull(cache.get("msl", key("e4")));
        assertTrue(Files.exists(cache.directory().resolve(ShaderDiskCache.ENV_FILE)), "environment file is never evicted");
    }

    @Test
    void rejectsKeysThatCouldEscapeTheDirectory(@TempDir final Path root) {
        ShaderDiskCache cache = ShaderDiskCache.open(root, "env-a", 64 * MB);
        assertThrows(IllegalArgumentException.class, () -> cache.get("msl", "../../etc"));
        assertThrows(IllegalArgumentException.class, () -> cache.put("../x", key("k"), new byte[0]));
    }

    @Test
    void unwritableRootGivesNoCache(@TempDir final Path root) throws IOException {
        Path file = root.resolve("not-a-directory");
        Files.writeString(file, "x");
        assertNull(ShaderDiskCache.open(file, "env-a", 64 * MB));
    }
}
