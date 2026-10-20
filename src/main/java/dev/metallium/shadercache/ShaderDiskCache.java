/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.shadercache;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Persistent content-addressed store for shader compile results ({@code <root>/<env>/<namespace>/<key>.bin}).
 *
 * <ul>
 *     <li>Versioned: {@code <env>} is a hash of the environment string (Metallium version, macOS version, GPU, MSL
 *     version, translator options...). Opening the cache with a new environment deletes the other environment
 *     directories, so stale results can never be used.</li>
 *     <li>Corruption-safe: every entry carries a magic, format version, length and CRC32; an entry that fails
 *     validation is deleted and reported as a miss (the caller then compiles from source). Writes go to a temp file
 *     that is atomically renamed into place.</li>
 *     <li>Size-capped: least recently used entries (file modification time, touched on every hit) are evicted when the
 *     environment directory grows beyond {@code maxBytes}; files other components keep there (e.g. a Metal binary
 *     archive) count towards the cap and are evicted the same way.</li>
 * </ul>
 * Thread-safe; IO errors never propagate (a failed read is a miss, a failed write is ignored).
 */
public final class ShaderDiskCache {
    static final int FORMAT = 1;
    private static final int MAGIC = 0x4D4C5343; // "MLSC"
    private static final int HEADER_BYTES = 4 + 4 + 4 + 8;
    private static final String ENV_PREFIX = "env-";
    static final String ENV_FILE = "environment.txt";

    private final Path directory;
    private final long maxBytes;
    private final AtomicLong sizeBytes = new AtomicLong();
    private final Object pruneLock = new Object();
    public final LongAdder corruptEntries = new LongAdder();
    public final LongAdder evictedEntries = new LongAdder();

    private ShaderDiskCache(final Path directory, final long maxBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    /**
     * Opens (creating if needed) the cache for {@code environment} under {@code root}; returns null if the directory
     * cannot be created. Directories of other environments under {@code root} are deleted.
     */
    public static @Nullable ShaderDiskCache open(final Path root, final String environment, final long maxBytes) {
        String envName = ENV_PREFIX + CacheKey.of("environment").add(environment).hex().substring(0, 16);
        Path directory = root.resolve(envName);
        try {
            Files.createDirectories(directory);
            Path envFile = directory.resolve(ENV_FILE);
            String expected = environment + "\nformat=" + FORMAT + "\n";
            if (!Files.exists(envFile) || !expected.equals(Files.readString(envFile))) {
                Files.writeString(envFile, expected);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(root, ENV_PREFIX + "*")) {
            for (Path sibling : siblings) {
                if (!sibling.getFileName().toString().equals(envName)) {
                    deleteRecursively(sibling);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // stale environments are only wasted space
        }
        ShaderDiskCache cache = new ShaderDiskCache(directory, maxBytes);
        cache.sizeBytes.set(cache.computeSize());
        cache.pruneIfNeeded();
        return cache;
    }

    public Path directory() {
        return this.directory;
    }

    public long sizeBytes() {
        return this.sizeBytes.get();
    }

    public long maxBytes() {
        return this.maxBytes;
    }

    Path entryPath(final String namespace, final String key) {
        if (!key.matches("[0-9a-f]{8,}") || !namespace.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("bad cache key " + namespace + "/" + key);
        }
        return this.directory.resolve(namespace).resolve(key.substring(0, 2)).resolve(key + ".bin");
    }

    /** The stored value, or null on a miss or an invalid (then deleted) entry. */
    public byte @Nullable [] get(final String namespace, final String key) {
        Path path = entryPath(namespace, key);
        byte[] file;
        try {
            file = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        byte[] payload = decode(file);
        if (payload == null) {
            this.corruptEntries.increment();
            delete(path);
            return null;
        }
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException | RuntimeException ignored) {
            // LRU order is best effort
        }
        return payload;
    }

    public void put(final String namespace, final String key, final byte[] value) {
        Path path = entryPath(namespace, key);
        byte[] file = encode(value);
        try {
            Files.createDirectories(path.getParent());
            long previous = Files.exists(path) ? Files.size(path) : 0L;
            writeAtomically(path, file);
            this.sizeBytes.addAndGet(file.length - previous);
        } catch (IOException | RuntimeException e) {
            return;
        }
        pruneIfNeeded();
    }

    /** Writes {@code bytes} to a temp file next to {@code target} and renames it over {@code target}. */
    public static void writeAtomically(final Path target, final byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + "." + Thread.currentThread().threadId() + ".tmp");
        Files.write(temp, bytes);
        moveAtomically(temp, target);
    }

    public static void moveAtomically(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Re-reads the directory size, e.g. after another component replaced a large file in it. */
    public void refreshSize() {
        this.sizeBytes.set(computeSize());
        pruneIfNeeded();
    }

    static byte[] encode(final byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC).putInt(FORMAT).putInt(payload.length).putLong(crc.getValue()).put(payload);
        return buffer.array();
    }

    static byte @Nullable [] decode(final byte[] file) {
        if (file.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.getInt() != FORMAT) {
            return null;
        }
        int length = buffer.getInt();
        long expectedCrc = buffer.getLong();
        if (length != file.length - HEADER_BYTES) {
            return null;
        }
        byte[] payload = new byte[length];
        buffer.get(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        return crc.getValue() == expectedCrc ? payload : null;
    }

    private long computeSize() {
        try (Stream<Path> files = Files.walk(this.directory)) {
            return files.filter(Files::isRegularFile).mapToLong(ShaderDiskCache::sizeOf).sum();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    private void pruneIfNeeded() {
        if (this.sizeBytes.get() <= this.maxBytes) {
            return;
        }
        synchronized (this.pruneLock) {
            if (this.sizeBytes.get() <= this.maxBytes) {
                return;
            }
            record Entry(Path path, long size, long modified) {
            }
            List<Entry> entries = new ArrayList<>();
            try (Stream<Path> files = Files.walk(this.directory)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().equals(ENV_FILE))
                        .forEach(path -> {
                            try {
                                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                                entries.add(new Entry(path, attributes.size(), attributes.lastModifiedTime().toMillis()));
                            } catch (IOException | RuntimeException ignored) {
                                // vanished concurrently
                            }
                        });
            } catch (IOException | RuntimeException e) {
                return;
            }
            entries.sort(Comparator.comparingLong(Entry::modified));
            long total = entries.stream().mapToLong(Entry::size).sum();
            long target = this.maxBytes * 3 / 4;
            for (Entry entry : entries) {
                if (total <= target) {
                    break;
                }
                if (delete(entry.path())) {
                    total -= entry.size();
                    this.evictedEntries.increment();
                }
            }
            this.sizeBytes.set(computeSize());
        }
    }

    private static long sizeOf(final Path path) {
        try {
            return Files.size(path);
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    private static boolean delete(final Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Deletes everything under {@code root} (best effort). */
    public static void deleteRecursively(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(ShaderDiskCache::delete);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }
}
