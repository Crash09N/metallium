/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import dev.metallium.Metallium;
import dev.metallium.shadercache.CacheKey;
import dev.metallium.shadercache.ShaderCache;
import dev.metallium.shadercache.ShaderCacheStats;
import dev.metallium.shadercache.ShaderDiskCache;
import io.github.kokodio.metaljvm.foundation.NSArray;
import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.foundation.NSURL;
import io.github.kokodio.metaljvm.metal.MTLBinaryArchive;
import io.github.kokodio.metaljvm.metal.MTLBinaryArchiveDescriptor;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLPipelineOption;
import io.github.kokodio.metaljvm.metal.MTLRenderPipelineDescriptor;
import io.github.kokodio.metaljvm.metal.MTLRenderPipelineState;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent pipeline compile cache of one {@link MetalDevice}, in {@code <game dir>/.metallium-cache/env-<hash>/}:
 * <ul>
 *     <li>SPIR-V to MSL results (namespace {@code msl} of the {@link ShaderDiskCache}, see
 *     {@link MetalCrossShaderCompiler}) and GLSL to SPIR-V results of the core translator (namespace {@code glsl});</li>
 *     <li>a Metal binary archive ({@code pipelines.mtlarchive}) with every pipeline state built so far. Pipeline-state
 *     creation looks it up first ({@code binaryArchives} + {@code FailOnBinaryArchiveMiss}); misses are added to it
 *     (that add is the backend compile) and it is re-serialized in the background. Its file is also a metallib: it is
 *     opened as an {@link MTLLibrary} at startup, and functions found there skip the MSL front-end compile entirely
 *     (the expensive part, ~40 ms per library when the system Metal cache misses);</li>
 *     <li>an index of the attachment-layout variants each pipeline needed at draw time ({@code variants.txt}); next
 *     session they are built on the compile thread together with the pipeline instead of lazily on the render
 *     thread.</li>
 * </ul>
 * Corruption-safe: an archive that cannot be opened is replaced; one that was in use when the game died (marker file)
 * is discarded; a pipeline that fails with archive functions is rebuilt from source and the archive dropped.
 */
@Environment(EnvType.CLIENT)
final class MetalPipelineCache {
    static final String MSL_NAMESPACE = "msl";
    private static final String ARCHIVE_FILE = "pipelines.mtlarchive";
    private static final String ARCHIVE_IN_USE_MARKER = "pipelines.mtlarchive.in-use";
    private static final String VARIANTS_FILE = "variants.txt";
    private static final long SAVE_INTERVAL_SECONDS = 20;

    private final @Nullable ShaderDiskCache disk;
    private final MTLDevice device;
    /** Read lock: archive lookups/adds (concurrent); write lock: serialize, discard, close. */
    private final ReentrantReadWriteLock archiveLock = new ReentrantReadWriteLock();
    private volatile @Nullable MTLBinaryArchive archive;
    /** The archive file of the previous session opened as a library (null if there was none). */
    private volatile @Nullable MTLLibrary archiveLibrary;
    private volatile boolean archiveDirty;
    private volatile boolean archiveAddFailed;
    private volatile boolean archiveDiscarded;
    private boolean inUseMarkerPresent;
    private final Map<String, Set<Integer>> variants = new ConcurrentHashMap<>();
    private volatile boolean variantsDirty;
    private final @Nullable ScheduledExecutorService saver;

    private MetalPipelineCache(final @Nullable ShaderDiskCache disk, final MTLDevice device) {
        this.disk = disk;
        this.device = device;
        if (disk != null) {
            loadVariants();
            loadArchive(disk);
            this.saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Metallium shader cache writer");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            this.saver.scheduleWithFixedDelay(this::saveQuietly, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } else {
            this.saver = null;
        }
    }

    static MetalPipelineCache open(final MTLDevice device, final String deviceName) {
        if (!ShaderCache.ENABLED) {
            Metallium.LOGGER.info("[metallium] Shader cache disabled (-Dmetallium.shaderCache=false)");
            return new MetalPipelineCache(null, device);
        }
        ShaderDiskCache disk = null;
        try {
            disk = ShaderCache.open(ShaderCache.root(FabricLoader.getInstance().getGameDir()), environment(deviceName));
        } catch (RuntimeException | LinkageError e) {
            Metallium.LOGGER.warn("[metallium] Shader cache unavailable: {}", e.toString());
        }
        if (disk == null) {
            Metallium.LOGGER.warn("[metallium] Shader cache directory not usable; compiling shaders from source");
            return new MetalPipelineCache(null, device);
        }
        MetalPipelineCache cache = new MetalPipelineCache(disk, device);
        Metallium.LOGGER.info("[metallium] Shader cache {} ({} of {} MiB): binary archive {}, {} recorded pipeline variants",
                disk.directory(), disk.sizeBytes() >> 20, disk.maxBytes() >> 20,
                cache.archive == null ? "unavailable" : cache.archiveLibrary != null ? "loaded" : "new", cache.variants.size());
        return cache;
    }

    /**
     * Everything a cached result depends on besides its own content key. A change of any part starts a fresh cache
     * directory (and deletes the old one). The translator classes are hashed, so a code change to the SPIR-V/GLSL
     * translation (options, remapping) invalidates old entries without a version bump.
     */
    static String environment(final String deviceName) {
        return "metallium=" + modVersion(Metallium.MOD_ID)
                + "\nminecraft=" + modVersion("minecraft")
                + "\nmacos=" + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "")
                + "\ngpu=" + deviceName
                + "\nmsl=" + MetalCrossShaderCompiler.MSL_VERSION_STRING
                + "\nlwjgl=" + org.lwjgl.Version.getVersion()
                + "\ntranslators=" + classHash(MetalCrossShaderCompiler.class, MetalCrossShaderCompiler.MslShader.class,
                dev.metallium.shaders.GlslProgramTranslator.class);
    }

    private static String modVersion(final String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    private static String classHash(final Class<?>... classes) {
        CacheKey key = CacheKey.of("classes");
        for (Class<?> type : classes) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                key.add(in == null ? type.getName() : new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                key.add(type.getName());
            }
        }
        return key.hex().substring(0, 16);
    }

    boolean enabled() {
        return this.disk != null;
    }

    byte @Nullable [] get(final String namespace, final String key) {
        return this.disk == null ? null : this.disk.get(namespace, key);
    }

    void put(final String namespace, final String key, final byte[] value) {
        if (this.disk != null) {
            this.disk.put(namespace, key, value);
        }
    }

    // ---------------------------------------------------------------- binary archive

    private void loadArchive(final ShaderDiskCache disk) {
        Path directory = disk.directory();
        Path file = directory.resolve(ARCHIVE_FILE);
        Path marker = directory.resolve(ARCHIVE_IN_USE_MARKER);
        try {
            if (Files.exists(marker)) {
                Metallium.LOGGER.warn("[metallium] The game stopped early while the pipeline binary archive was in use; discarding the archive");
                Files.deleteIfExists(file);
            }
            if (Files.exists(file) && Files.size(file) > disk.maxBytes() / 2) {
                Metallium.LOGGER.info("[metallium] Pipeline binary archive exceeds half the cache size cap; starting a new one");
                Files.deleteIfExists(file);
            }
            Files.writeString(marker, "in use since " + java.time.Instant.now());
            this.inUseMarkerPresent = true;
        } catch (IOException e) {
            Metallium.LOGGER.warn("[metallium] Pipeline binary archive disabled: {}", e.toString());
            return;
        }
        boolean existing = Files.exists(file);
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            if (existing) {
                try {
                    this.archive = newArchive(file);
                    this.archiveLibrary = this.device.newLibraryWithURL(NSURL.fileURL(file.toAbsolutePath().toString()));
                } catch (NSErrorException e) {
                    Metallium.LOGGER.warn("[metallium] Pipeline binary archive could not be opened ({}); starting a new one", e.getMessage());
                    if (this.archive != null) {
                        this.archive.release();
                        this.archive = null;
                    }
                    this.archiveLibrary = null;
                }
            }
            if (this.archive == null) {
                this.archive = newArchive(null);
            }
        } catch (NSErrorException e) {
            Metallium.LOGGER.warn("[metallium] Pipeline binary archive unavailable: {}", e.getMessage());
            this.archive = null;
        }
    }

    private MTLBinaryArchive newArchive(final @Nullable Path file) {
        MTLBinaryArchiveDescriptor descriptor = MTLBinaryArchiveDescriptor.alloc().init();
        try {
            if (file != null) {
                descriptor.setUrl(NSURL.fileURL(file.toAbsolutePath().toString()));
            }
            return this.device.newBinaryArchive(descriptor);
        } finally {
            descriptor.release();
        }
    }

    /** True while pipelines may take functions or binaries from the archive. */
    boolean archiveInUse() {
        return this.archive != null && !this.archiveDiscarded;
    }

    /** The precompiled function {@code name} from the previous sessions' archive, or null (compile from source). */
    @Nullable
    MTLFunction archivedFunction(final String name) {
        MTLLibrary library = this.archiveLibrary;
        if (library == null || this.archiveDiscarded) {
            return null;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLFunction function = library.newFunctionWithName(name);
            if (function != null) {
                ShaderCacheStats.archivedFunctions.increment();
            }
            return function;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Stops using the archive for the rest of the session and deletes its file (a pipeline failed with it). */
    void discardArchive() {
        this.archiveDiscarded = true;
        this.archiveLibrary = null; // not released: functions taken from it may still be in use
    }

    /**
     * Creates a pipeline state, taking its GPU binary from the binary archive when present and otherwise adding it to
     * the archive first (the add performs the backend compile; the creation then hits the archive). Returns null and
     * logs on failure, like {@link MetalUtilities#newRenderPipelineState}.
     */
    @Nullable
    MTLRenderPipelineState newRenderPipelineState(final MTLRenderPipelineDescriptor descriptor) {
        if (!archiveInUse()) {
            return MetalUtilities.newRenderPipelineState(this.device, descriptor);
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            this.archiveLock.readLock().lock();
            try {
                MTLBinaryArchive archive = this.archive;
                if (archive == null || this.archiveDiscarded) {
                    return MetalUtilities.newRenderPipelineState(this.device, descriptor);
                }
                descriptor.setBinaryArchives(NSArray.arrayWithObject(MTLBinaryArchive::new, archive));
                try {
                    MTLRenderPipelineState state = this.device.newRenderPipelineState(descriptor, MTLPipelineOption.FailOnBinaryArchiveMiss, MemorySegment.NULL);
                    ShaderCacheStats.archiveHits.increment();
                    return state;
                } catch (NSErrorException miss) {
                    ShaderCacheStats.archiveMisses.increment();
                }
                if (!this.archiveAddFailed) {
                    try {
                        archive.addRenderPipelineFunctions(descriptor);
                        this.archiveDirty = true;
                    } catch (NSErrorException e) {
                        this.archiveAddFailed = true;
                        Metallium.LOGGER.warn("[metallium] Could not add a pipeline to the binary archive ({}); no further archive writes this session", e.getMessage());
                    }
                }
                return MetalUtilities.newRenderPipelineState(this.device, descriptor);
            } finally {
                this.archiveLock.readLock().unlock();
            }
        }
    }

    // ---------------------------------------------------------------- variant index

    static String variantKey(final String pipelineName, final String vertexEntryPoint, final String fragmentEntryPoint, final int fullLayout) {
        return CacheKey.of("variant").add(pipelineName).add(vertexEntryPoint).add(fragmentEntryPoint).add(fullLayout).hex();
    }

    Set<Integer> recordedVariants(final String key) {
        Set<Integer> layouts = this.variants.get(key);
        return layouts == null ? Set.of() : layouts;
    }

    void recordVariant(final String key, final int layout) {
        if (this.disk != null && this.variants.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(layout)) {
            this.variantsDirty = true;
        }
    }

    private void loadVariants() {
        for (String line : readLines(VARIANTS_FILE)) {
            String[] parts = line.split(" ");
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}")) {
                continue;
            }
            try {
                this.variants.computeIfAbsent(parts[0], k -> ConcurrentHashMap.newKeySet()).add(Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
                // skip the line
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private List<String> readLines(final String name) {
        try {
            return Files.readAllLines(this.disk.directory().resolve(name), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (Throwable t) {
            Metallium.LOGGER.warn("[metallium] Saving the shader cache failed: {}", t.toString());
        }
    }

    synchronized void save() throws IOException {
        if (this.disk == null) {
            return;
        }
        Path directory = this.disk.directory();
        boolean changed = false;
        if (this.variantsDirty) {
            this.variantsDirty = false;
            List<String> lines = new ArrayList<>();
            this.variants.forEach((key, layouts) -> layouts.forEach(layout -> lines.add(key + " " + layout)));
            ShaderDiskCache.writeAtomically(directory.resolve(VARIANTS_FILE), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            changed = true;
        }
        if (this.archiveDiscarded) {
            changed |= Files.deleteIfExists(directory.resolve(ARCHIVE_FILE));
        } else if (this.archiveDirty) {
            Path temp = directory.resolve(ARCHIVE_FILE + "." + ProcessHandle.current().pid() + ".tmp");
            boolean written = false;
            this.archiveLock.writeLock().lock();
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                MTLBinaryArchive archive = this.archive;
                if (archive != null) {
                    this.archiveDirty = false;
                    Files.deleteIfExists(temp);
                    archive.serializeToURL(NSURL.fileURL(temp.toAbsolutePath().toString()));
                    written = true;
                }
            } catch (NSErrorException e) {
                Metallium.LOGGER.warn("[metallium] Could not write the pipeline binary archive: {}", e.getMessage());
            } finally {
                this.archiveLock.writeLock().unlock();
            }
            if (written) {
                ShaderDiskCache.moveAtomically(temp, directory.resolve(ARCHIVE_FILE));
                changed = true;
            } else {
                Files.deleteIfExists(temp);
            }
        }
        if (this.inUseMarkerPresent) {
            // The archive has been in use for a while without the game dying: trust it next session.
            Files.deleteIfExists(directory.resolve(ARCHIVE_IN_USE_MARKER));
            this.inUseMarkerPresent = false;
        }
        if (changed) {
            this.disk.refreshSize();
        }
    }

    void close() {
        if (this.saver != null) {
            this.saver.shutdownNow();
        }
        saveQuietly();
        this.archiveLock.writeLock().lock();
        try {
            if (this.archive != null) {
                this.archive.release();
                this.archive = null;
            }
        } finally {
            this.archiveLock.writeLock().unlock();
        }
    }
}
