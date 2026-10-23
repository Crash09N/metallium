/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend;

import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.shadercache.CacheKey;
import de.nprmedia.nativium.shadercache.ShaderCache;
import de.nprmedia.nativium.shadercache.ShaderCacheStats;
import de.nprmedia.nativium.shadercache.ShaderDiskCache;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Persistent pipeline compile cache of one {@link MetalDevice}, in {@code <game dir>/.nativium-cache/env-<hash>/}:
 * <ul>
 *     <li>SPIR-V to MSL results (namespace {@code msl} of the {@link ShaderDiskCache}, see
 *     {@link MetalCrossShaderCompiler}) and GLSL to SPIR-V results of the core translator (namespace {@code glsl});</li>
 *     <li>a Metal binary archive ({@code pipelines.mtlarchive}) with every pipeline state built so far. Pipeline-state
 *     creation looks it up first ({@code binaryArchives} + {@code FailOnBinaryArchiveMiss}); misses are compiled
 *     normally and, once created, added to this session's archive by the writer thread alone, which serializes it
 *     once pipeline creation has quietened (crash marker around it, see {@link #SERIALIZE_MARKER}). Its file is also a metallib: it is
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
    /** Archive segments: {@code pipelines.mtlarchive} (older versions) and {@code pipelines-<time>.mtlarchive}. */
    private static final String ARCHIVE_PREFIX = "pipelines";
    private static final String ARCHIVE_SUFFIX = ".mtlarchive";
    /** Segments (sessions that compiled new pipelines) before the archive is started over. */
    private static final int MAX_SEGMENTS = 24;
    private static final String ARCHIVE_IN_USE_MARKER = "pipelines.mtlarchive.in-use";
    /**
     * Present while the writer serializes an archive. Metal's {@code serializeToURL} can crash the process natively
     * (2026-10-23: EXC_BAD_ACCESS in {@code -[_MTLBinaryArchive materializeAirNTFromOnlineForSlice:entryList:]}), which
     * Java cannot catch; if this file is still there at the next start, that is what happened, and archive writing
     * stays off for this cache environment (the file is kept; a new Nativium/macOS/GPU version starts a new one).
     */
    static final String SERIALIZE_MARKER = "pipelines.serializing";
    /** {@code -Dnativium.shaderCache.writeArchive=false}: use earlier sessions' archive segments but never write one. */
    private static final boolean WRITE_ARCHIVE = !"false".equalsIgnoreCase(System.getProperty("nativium.shaderCache.writeArchive"));
    /** The writer waits until no new pipeline has been created for this long (start-up and reload bursts) ... */
    static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** ... but not longer than this. */
    static final long MAX_DEFER_NANOS = TimeUnit.SECONDS.toNanos(120);
    private static final String VARIANTS_FILE = "variants.txt";
    /** Descriptor keys of pipelines Apple's archiver could not write; never added to the archive again. */
    private static final String EXCLUDED_FILE = "pipelines.excluded";
    private static final long SAVE_INTERVAL_SECONDS = 20;
    /** Most pipelines left out of the archive in one rebuild before giving up on archive writes for the session. */
    private static final int MAX_EXCLUDED_PER_REBUILD = 8;
    /** {@code -Dnativium.shaderCache.logMisses=true}: log every binary archive miss (which pipelines get compiled). */
    private static final boolean LOG_MISSES = Boolean.getBoolean("nativium.shaderCache.logMisses");
    private static final Pattern OBJECT_ADDRESS = Pattern.compile("<[A-Za-z_][A-Za-z0-9_]*: 0x[0-9a-fA-F]+>");

    private final @Nullable ShaderDiskCache disk;
    private final MTLDevice device;
    /** Read lock: archive lookups and session-add bookkeeping on compile threads; write lock: close. */
    private final ReentrantReadWriteLock archiveLock = new ReentrantReadWriteLock();
    /** True once the earlier sessions' segments are loaded (a binary archive is usable this session). */
    private volatile boolean archiveAvailable;
    /**
     * The archive written to {@link #sessionSegment}. Owned by the writer ({@link #save}, under this object's monitor)
     * and nothing else: compile threads never add to it and no pipeline descriptor references it in
     * {@code binaryArchives}, so the writer adds and serializes with no other thread touching it. Built only from
     * descriptors whose pipeline state was created successfully.
     */
    private @Nullable MTLBinaryArchive writerArchive;
    /** Descriptor keys already in {@link #writerArchive}. Guarded by this object's monitor. */
    private final Set<String> writerKeys = new java.util.HashSet<>();
    /** Archive segments of earlier sessions (read only) and the same files opened as libraries. */
    private final List<MTLBinaryArchive> loadedArchives = new ArrayList<>();
    private final List<MTLLibrary> archiveLibraries = new ArrayList<>();
    /** The earlier sessions' segments, which pipeline lookups search (never added to or serialized). */
    private volatile @Nullable NSArray<MTLBinaryArchive> archiveArray;
    private final String sessionSegment = ARCHIVE_PREFIX + "-" + System.currentTimeMillis() + ARCHIVE_SUFFIX;
    private volatile boolean archiveDirty;
    /** {@link System#nanoTime} of the last pipeline recorded for the archive (writes wait for a quiet moment). */
    private volatile long lastAddNanos = System.nanoTime();
    /** When the writer first put off a write because pipelines were still being created; 0 if it has not. */
    private long deferredSinceNanos;
    /** Archive writes stopped for this session (an add failed in an unexpected way, or a rebuild gave up). */
    private volatile boolean archiveWritesDisabled;
    private volatile boolean archiveDiscarded;
    /** Full-descriptor keys of every pipeline recorded for this session's archive: each is recorded exactly once. */
    private final Set<String> addedKeys = ConcurrentHashMap.newKeySet();
    /**
     * Retained copies of the descriptors of this session's new pipelines (insertion order, only pipelines whose
     * state was created), which the writer adds to {@link #writerArchive}. Guarded by itself.
     */
    private final Map<String, MTLRenderPipelineDescriptor> sessionAdds = new LinkedHashMap<>();
    private final Set<String> excludedKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean excludedDirty;
    private boolean inUseMarkerPresent;
    private final Map<String, Set<Integer>> variants = new ConcurrentHashMap<>();
    private volatile boolean variantsDirty;
    private final @Nullable ScheduledExecutorService saver;

    private MetalPipelineCache(final @Nullable ShaderDiskCache disk, final MTLDevice device) {
        this.disk = disk;
        this.device = device;
        if (disk != null) {
            loadVariants();
            loadExcluded();
            loadArchive(disk);
            this.saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Nativium shader cache writer");
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
            Nativium.LOGGER.info("[nativium] Shader cache disabled (-Dnativium.shaderCache=false)");
            return new MetalPipelineCache(null, device);
        }
        ShaderDiskCache disk = null;
        try {
            de.nprmedia.nativium.LegacyMigration.dropLegacyShaderCacheOnce(FabricLoader.getInstance().getGameDir());
            disk = ShaderCache.open(ShaderCache.root(FabricLoader.getInstance().getGameDir()), environment(deviceName));
        } catch (RuntimeException | LinkageError e) {
            Nativium.LOGGER.warn("[nativium] Shader cache unavailable: {}", e.toString());
        }
        if (disk == null) {
            Nativium.LOGGER.warn("[nativium] Shader cache directory not usable; compiling shaders from source");
            return new MetalPipelineCache(null, device);
        }
        MetalPipelineCache cache = new MetalPipelineCache(disk, device);
        Nativium.LOGGER.info("[nativium] Shader cache {} ({} of {} MiB): binary archive {}, {} recorded pipeline variants",
                disk.directory(), disk.sizeBytes() >> 20, disk.maxBytes() >> 20,
                !cache.archiveAvailable ? "unavailable" : (cache.loadedArchives.isEmpty() ? "new" : "loaded (" + cache.loadedArchives.size() + " segments)")
                        + (cache.archiveWritesDisabled ? ", not written this session" : ""),
                cache.variants.size());
        return cache;
    }

    /**
     * Everything a cached result depends on besides its own content key. A change of any part starts a fresh cache
     * directory (and deletes the old one). The translator classes are hashed, so a code change to the SPIR-V/GLSL
     * translation (options, remapping) invalidates old entries without a version bump.
     */
    static String environment(final String deviceName) {
        return "nativium=" + modVersion(Nativium.MOD_ID)
                + "\nminecraft=" + modVersion("minecraft")
                + "\nmacos=" + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "")
                + "\ngpu=" + deviceName
                + "\nmsl=" + MetalCrossShaderCompiler.MSL_VERSION_STRING
                + "\nlwjgl=" + org.lwjgl.Version.getVersion()
                + "\ntranslators=" + classHash(MetalCrossShaderCompiler.class, MetalCrossShaderCompiler.MslShader.class,
                de.nprmedia.nativium.shaders.GlslProgramTranslator.class);
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

    /** Archive segment files of earlier sessions, oldest first (the legacy {@code pipelines.mtlarchive} sorts first). */
    private static List<Path> segmentFiles(final Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX);
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void loadArchive(final ShaderDiskCache disk) {
        Path directory = disk.directory();
        Path marker = directory.resolve(ARCHIVE_IN_USE_MARKER);
        try {
            List<Path> segments = segmentFiles(directory);
            long total = 0;
            for (Path segment : segments) {
                total += Files.size(segment);
            }
            String reset = Files.exists(marker) ? "the game stopped early while it was in use"
                    : total > disk.maxBytes() / 2 ? "it exceeds half the cache size cap"
                    : segments.size() >= MAX_SEGMENTS ? "it has " + segments.size() + " segments"
                    : null;
            if (reset != null && !segments.isEmpty()) {
                Nativium.LOGGER.warn("[nativium] Discarding the pipeline binary archive ({}); starting a new one", reset);
                for (Path segment : segments) {
                    Files.deleteIfExists(segment);
                }
            }
            deleteStaleTempFiles(directory);
            if (serializeCrashed(directory)) {
                this.archiveWritesDisabled = true;
                Nativium.LOGGER.warn("[nativium] An earlier session crashed inside Metal while writing the pipeline binary archive; "
                        + "archive writing stays off for this shader cache (delete {} to try again)", directory.resolve(SERIALIZE_MARKER));
            } else if (!WRITE_ARCHIVE) {
                this.archiveWritesDisabled = true;
            }
            Files.writeString(marker, "in use since " + java.time.Instant.now());
            this.inUseMarkerPresent = true;
        } catch (IOException e) {
            Nativium.LOGGER.warn("[nativium] Pipeline binary archive disabled: {}", e.toString());
            return;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            for (Path segment : segmentFiles(directory)) {
                MTLBinaryArchive loaded = null;
                try {
                    loaded = newArchive(segment);
                    MTLLibrary library = this.device.newLibraryWithURL(NSURL.fileURL(segment.toAbsolutePath().toString()));
                    this.loadedArchives.add(loaded);
                    this.archiveLibraries.add(library);
                } catch (NSErrorException e) {
                    Nativium.LOGGER.warn("[nativium] Pipeline binary archive segment {} could not be opened ({}); deleting it", segment.getFileName(), e.getMessage());
                    if (loaded != null) {
                        loaded.release();
                    }
                    try {
                        Files.deleteIfExists(segment);
                    } catch (IOException ignored) {
                        // tried
                    }
                }
            }
            // This session's new pipelines go into a fresh archive of their own (the writer's, see save): Metal
            // re-serializes an archive opened from a file at several times its size once anything is added to it
            // (measured: 30 pipelines 755 KB, +1 pipeline 2.2 MB; Iris game test 9.9 MB -> 115 MB), and serializing an
            // unchanged one moves its file.
            updateArchiveArray();
            this.archiveAvailable = true;
        } catch (RuntimeException e) {
            Nativium.LOGGER.warn("[nativium] Pipeline binary archive unavailable: {}", e.getMessage());
        }
    }

    /** True if a session died while serializing an archive in {@code directory} (its marker is still there). */
    static boolean serializeCrashed(final Path directory) {
        return Files.exists(directory.resolve(SERIALIZE_MARKER));
    }

    /** Writes the serialize marker; false if it cannot be written (then nothing may be serialized). */
    static boolean beginSerialize(final Path directory) {
        try {
            Files.writeString(directory.resolve(SERIALIZE_MARKER), "serializing since " + java.time.Instant.now()
                    + " (pid " + ProcessHandle.current().pid() + ")\n");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Removes the serialize marker once serializing returned (successfully or with an error, not a crash). */
    static void endSerialize(final Path directory) {
        try {
            Files.deleteIfExists(directory.resolve(SERIALIZE_MARKER));
        } catch (IOException ignored) {
            // the next start then turns archive writing off, which is safe
        }
    }

    /**
     * Whether the writer may serialize now: on close always; otherwise once no pipeline was recorded for
     * {@link #QUIET_NANOS}, or when it has already waited {@link #MAX_DEFER_NANOS} for that.
     */
    static boolean quietEnough(final long now, final long lastAddNanos, final long deferredSinceNanos, final boolean closing) {
        return closing || now - lastAddNanos >= QUIET_NANOS
                || (deferredSinceNanos != 0 && now - deferredSinceNanos >= MAX_DEFER_NANOS);
    }

    /** Temp files of archive writes that never finished (the process died). */
    private static void deleteStaleTempFiles(final Path directory) {
        try (var stream = Files.list(directory)) {
            for (Path path : stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(ARCHIVE_PREFIX) && name.endsWith(".tmp");
            }).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // left for next time
        }
    }

    /** {@link #archiveArray} = earlier sessions' segments. Called once, before use. */
    private void updateArchiveArray() {
        List<MTLBinaryArchive> all = new ArrayList<>(this.loadedArchives);
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment pointers = arena.allocate(java.lang.foreign.ValueLayout.ADDRESS, Math.max(1, all.size()));
            for (int i = 0; i < all.size(); i++) {
                pointers.setAtIndex(java.lang.foreign.ValueLayout.ADDRESS, i, MemorySegment.ofAddress(all.get(i).handle()));
            }
            NSArray<MTLBinaryArchive> array = NSArray.arrayWithObjects(MTLBinaryArchive::new, pointers, all.size());
            array.retain();
            this.archiveArray = array;
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
        return this.archiveAvailable && !this.archiveDiscarded;
    }

    /** The precompiled function {@code name} from the earlier sessions' archive segments, or null (compile from source). */
    @Nullable
    MTLFunction archivedFunction(final String name) {
        if (this.archiveDiscarded) {
            return null;
        }
        for (MTLLibrary library : this.archiveLibraries) {
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                MTLFunction function = library.newFunctionWithName(name);
                if (function != null) {
                    ShaderCacheStats.archivedFunctions.increment();
                    return function;
                }
            } catch (RuntimeException e) {
                // try the next segment
            }
        }
        return null;
    }

    /** Stops using the archive for the rest of the session and deletes its files (a pipeline failed with it). */
    void discardArchive() {
        this.archiveDiscarded = true;
        // The libraries are not released: functions taken from them may still be in use.
    }

    /**
     * Creates a pipeline state, taking its GPU binary from the earlier sessions' archive segments when present. A miss
     * is compiled normally and, once its state exists, its descriptor is recorded for the writer, which adds it to
     * this session's archive on its own thread (see {@link #writeArchive}). Compile threads never add to or serialize
     * an archive. Returns null and logs on failure, like {@link MetalUtilities#newRenderPipelineState}.
     */
    @Nullable
    MTLRenderPipelineState newRenderPipelineState(final MTLRenderPipelineDescriptor descriptor) {
        if (!archiveInUse()) {
            return MetalUtilities.newRenderPipelineState(this.device, descriptor);
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            this.archiveLock.readLock().lock();
            try {
                if (this.archiveDiscarded) {
                    return MetalUtilities.newRenderPipelineState(this.device, descriptor);
                }
                String key = descriptorKey(descriptor.description());
                if (this.excludedKeys.contains(key)) {
                    return MetalUtilities.newRenderPipelineState(this.device, descriptor);
                }
                if (!this.loadedArchives.isEmpty()) {
                    descriptor.setBinaryArchives(this.archiveArray);
                    try {
                        MTLRenderPipelineState state = this.device.newRenderPipelineState(descriptor, MTLPipelineOption.FailOnBinaryArchiveMiss, MemorySegment.NULL);
                        ShaderCacheStats.archiveHits.increment();
                        return state;
                    } catch (NSErrorException miss) {
                        // compiled below
                    }
                }
                ShaderCacheStats.archiveMisses.increment();
                if (LOG_MISSES) {
                    Nativium.LOGGER.info("[nativium] Binary archive miss: {} / {} ({}) key {}", functionName(descriptor.vertexFunction()),
                            functionName(descriptor.fragmentFunction()), descriptor.label(), key.substring(0, 12));
                }
                MTLRenderPipelineState state = MetalUtilities.newRenderPipelineState(this.device, descriptor);
                // Only pipelines that were created go into the archive, each exactly once.
                if (state != null && !this.archiveWritesDisabled && this.addedKeys.add(key)) {
                    MTLRenderPipelineDescriptor copy = new MTLRenderPipelineDescriptor(descriptor.copy().handle());
                    copy.setBinaryArchives(null);
                    synchronized (this.sessionAdds) {
                        this.sessionAdds.put(key, copy);
                    }
                    this.lastAddNanos = System.nanoTime();
                    this.archiveDirty = true;
                }
                return state;
            } finally {
                this.archiveLock.readLock().unlock();
            }
        }
    }

    /**
     * Key of everything in a render pipeline descriptor (functions by name, which are unique per translation; vertex
     * descriptor; attachment formats, blend state and write masks; raster state), from its Metal description with
     * the label and object addresses left out. The binary archives are not part of the description.
     */
    static String descriptorKey(final String description) {
        StringBuilder stable = new StringBuilder(description.length());
        description.lines()
                .filter(line -> !line.stripLeading().startsWith("label ="))
                .forEach(line -> stable.append(OBJECT_ADDRESS.matcher(line).replaceAll("")).append('\n'));
        return CacheKey.of("render-pipeline").add(stable.toString()).hex();
    }

    /** Leaves a pipeline out of the archive for good (this and later sessions); logs once per pipeline if asked. */
    private void exclude(final String key, final MTLRenderPipelineDescriptor descriptor, final String reason, final boolean log) {
        if (this.excludedKeys.add(key) && log) {
            Nativium.LOGGER.warn("[nativium] Pipeline {} / {} ({}) is left out of the binary archive: {}",
                    functionName(descriptor.vertexFunction()), functionName(descriptor.fragmentFunction()),
                    descriptor.label() == null ? "no label" : descriptor.label(), firstLine(reason));
        }
        this.excludedDirty = true;
    }

    private static String functionName(final @Nullable MTLFunction function) {
        return function == null ? "-" : function.name();
    }

    private static String firstLine(final @Nullable String message) {
        if (message == null) {
            return "unknown error";
        }
        int fragment = message.indexOf("expecting");
        return fragment >= 0 ? message.substring(fragment).lines().findFirst().orElse(message) : message.lines().findFirst().orElse(message);
    }

    private void loadExcluded() {
        for (String line : readLines(EXCLUDED_FILE)) {
            if (line.matches("[0-9a-f]{64}")) {
                this.excludedKeys.add(line);
            }
        }
    }

    /**
     * Writes this session's archive to {@code temp}: adds the recorded pipelines that are not in it yet to the
     * writer's own archive and serializes it, all on this thread, with no other thread adding to or using that
     * archive. The serialize marker is on disk the whole time (see {@link #SERIALIZE_MARKER}). On failure ("expecting
     * 'fragment' stage in pipeline no. N" and similar archiver errors, which reject the whole file) a new archive is
     * built from this session's pipelines, leaving out the ones it cannot write (found by bisection), and replaces the
     * writer's archive; earlier sessions' segments are not involved (they were written before). Call from
     * {@link #save} only.
     *
     * @return true if {@code temp} now holds a complete archive.
     */
    private boolean writeArchive(final Path directory, final Path temp) {
        if (!beginSerialize(directory)) {
            this.archiveWritesDisabled = true;
            Nativium.LOGGER.warn("[nativium] Pipeline binary archive not written: its marker file cannot be created; no archive writes this session");
            return false;
        }
        try {
            return writeArchiveMarked(temp);
        } finally {
            endSerialize(directory);
        }
    }

    private boolean writeArchiveMarked(final Path temp) {
        List<Map.Entry<String, MTLRenderPipelineDescriptor>> adds;
        synchronized (this.sessionAdds) {
            adds = new ArrayList<>(this.sessionAdds.entrySet());
        }
        if (adds.isEmpty()) {
            return false;
        }
        MTLBinaryArchive archive = this.writerArchive;
        if (archive == null) {
            try {
                archive = newArchive(null);
            } catch (NSErrorException e) {
                this.archiveWritesDisabled = true;
                Nativium.LOGGER.warn("[nativium] Pipeline binary archive could not be created ({}); no archive writes this session", e.getMessage());
                return false;
            }
            this.writerArchive = archive;
            this.writerKeys.clear();
        }
        List<Map.Entry<String, MTLRenderPipelineDescriptor>> rejected = new ArrayList<>();
        for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : adds) {
            if (this.writerKeys.contains(entry.getKey())) {
                continue;
            }
            try {
                archive.addRenderPipelineFunctions(entry.getValue());
                this.writerKeys.add(entry.getKey());
            } catch (NSErrorException e) {
                exclude(entry.getKey(), entry.getValue(), e.getMessage(), true);
                rejected.add(entry);
            }
        }
        if (!rejected.isEmpty()) {
            synchronized (this.sessionAdds) {
                rejected.forEach(entry -> this.sessionAdds.remove(entry.getKey()));
            }
            adds.removeAll(rejected);
        }
        String error = simulatedFailure(adds) ? SIMULATED_ERROR : trySerialize(archive, temp);
        if (error == null) {
            return true;
        }
        Nativium.LOGGER.warn("[nativium] The pipeline binary archive could not be written ({}); rebuilding it without the pipelines Metal cannot store",
                firstLine(error));
        long start = System.nanoTime();
        List<Map.Entry<String, MTLRenderPipelineDescriptor>> good = new ArrayList<>();
        List<Map.Entry<String, MTLRenderPipelineDescriptor>> rest = adds;
        List<Map.Entry<String, MTLRenderPipelineDescriptor>> bad = new ArrayList<>();
        MTLBinaryArchive rebuilt;
        while (true) {
            rebuilt = buildArchive(good, rest, temp);
            if (rebuilt != null) {
                break;
            }
            if (rest.isEmpty() || bad.size() >= MAX_EXCLUDED_PER_REBUILD) {
                this.archiveWritesDisabled = true;
                Nativium.LOGGER.warn("[nativium] Pipeline binary archive rebuild failed; no archive writes this session (earlier sessions' archive stays in use)");
                return false;
            }
            // Smallest prefix of rest that fails together with good; its last entry is a culprit.
            int low = 1;
            int high = rest.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                MTLBinaryArchive probe = buildArchive(good, rest.subList(0, mid), temp);
                if (probe != null) {
                    probe.release();
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            good.addAll(rest.subList(0, low - 1));
            bad.add(rest.get(low - 1));
            rest = rest.subList(low, rest.size());
        }
        Set<String> culprits = new java.util.TreeSet<>();
        for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : bad) {
            exclude(entry.getKey(), entry.getValue(), error, false);
            culprits.add(functionName(entry.getValue().vertexFunction()) + "/" + functionName(entry.getValue().fragmentFunction()));
        }
        synchronized (this.sessionAdds) {
            for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : bad) {
                this.sessionAdds.remove(entry.getKey());
            }
        }
        // Only this thread ever used the old archive, so it can go now. Pipelines recorded meanwhile are added to the
        // rebuilt one at the next save (they are not in writerKeys yet).
        archive.release();
        this.writerArchive = rebuilt;
        this.writerKeys.clear();
        good.forEach(entry -> this.writerKeys.add(entry.getKey()));
        rest.forEach(entry -> this.writerKeys.add(entry.getKey()));
        synchronized (this.sessionAdds) {
            if (this.sessionAdds.size() > this.writerKeys.size()) {
                this.archiveDirty = true;
            }
        }
        Nativium.LOGGER.info("[nativium] Pipeline binary archive rebuilt in {} ms: {} new pipelines kept, {} left out for good (compiled at load instead): {}",
                (System.nanoTime() - start) / 1_000_000, this.writerKeys.size(), bad.size(), culprits);
        return true;
    }

    /** A new archive of the given adds, written to {@code temp}; null if the add or the write fails. */
    private @Nullable MTLBinaryArchive buildArchive(final List<Map.Entry<String, MTLRenderPipelineDescriptor>> first,
                                                    final List<Map.Entry<String, MTLRenderPipelineDescriptor>> second, final Path temp) {
        MTLBinaryArchive candidate;
        try {
            candidate = newArchive(null);
        } catch (NSErrorException e) {
            return null;
        }
        try {
            for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : first) {
                candidate.addRenderPipelineFunctions(entry.getValue());
            }
            for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : second) {
                candidate.addRenderPipelineFunctions(entry.getValue());
            }
        } catch (NSErrorException e) {
            candidate.release();
            return null;
        }
        List<Map.Entry<String, MTLRenderPipelineDescriptor>> all = new ArrayList<>(first);
        all.addAll(second);
        if (simulatedFailure(all) || trySerialize(candidate, temp) != null) {
            candidate.release();
            return null;
        }
        return candidate;
    }

    /**
     * Test switch {@code -Dnativium.shaderCache.simulateArchiveFailure=true}: pretend the archiver rejects an archive
     * that holds two pipelines with the same fragment function and different state (the failure seen on 2026-10-18,
     * "expecting 'fragment' stage in pipeline no. N"), for the first such fragment function of the session. Exercises
     * the rebuild path, which cannot be triggered on demand on a healthy system.
     */
    private static final boolean SIMULATE_FAILURE = Boolean.getBoolean("nativium.shaderCache.simulateArchiveFailure");
    private static final String SIMULATED_ERROR = "simulated: expecting 'fragment' stage in pipeline no. '0'";
    private volatile @Nullable String simulatedFragment;

    private boolean simulatedFailure(final List<Map.Entry<String, MTLRenderPipelineDescriptor>> adds) {
        if (!SIMULATE_FAILURE) {
            return false;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : adds) {
            String fragment = functionName(entry.getValue().fragmentFunction());
            if (this.simulatedFragment == null && !seen.add(fragment)) {
                this.simulatedFragment = fragment;
            }
        }
        int count = 0;
        for (Map.Entry<String, MTLRenderPipelineDescriptor> entry : adds) {
            if (functionName(entry.getValue().fragmentFunction()).equals(this.simulatedFragment)) {
                count++;
            }
        }
        return count >= 2;
    }

    /** Serializes {@code archive} to {@code temp}; returns null on success, else the error message. */
    private static @Nullable String trySerialize(final MTLBinaryArchive archive, final Path temp) {
        try {
            Files.deleteIfExists(temp);
            archive.serializeToURL(NSURL.fileURL(temp.toAbsolutePath().toString()));
            return null;
        } catch (NSErrorException e) {
            return e.getMessage() == null ? "unknown error" : e.getMessage();
        } catch (IOException e) {
            return e.toString();
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
        saveQuietly(false);
    }

    private void saveQuietly(final boolean closing) {
        try {
            save(closing);
        } catch (Throwable t) {
            Nativium.LOGGER.warn("[nativium] Saving the shader cache failed: {}", t.toString());
        }
    }

    /** Writes what changed; {@code closing}: the game is stopping, so the archive is written without waiting. */
    synchronized void save(final boolean closing) throws IOException {
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
            for (Path segment : segmentFiles(directory)) {
                changed |= Files.deleteIfExists(segment);
            }
        } else if (this.archiveDirty && !this.archiveWritesDisabled
                && !quietEnough(System.nanoTime(), this.lastAddNanos, this.deferredSinceNanos, closing)) {
            // Pipelines are still being created (start-up, resource reload): write once that settles.
            if (this.deferredSinceNanos == 0) {
                this.deferredSinceNanos = System.nanoTime();
            }
        } else if (this.archiveDirty && !this.archiveWritesDisabled) {
            this.deferredSinceNanos = 0;
            Path temp = directory.resolve(this.sessionSegment + "." + ProcessHandle.current().pid() + ".tmp");
            boolean written;
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                this.archiveDirty = false;
                written = writeArchive(directory, temp);
            }
            if (written) {
                ShaderDiskCache.moveAtomically(temp, directory.resolve(this.sessionSegment));
                changed = true;
            } else {
                Files.deleteIfExists(temp);
            }
        }
        if (this.excludedDirty) {
            this.excludedDirty = false;
            ShaderDiskCache.writeAtomically(directory.resolve(EXCLUDED_FILE),
                    (String.join("\n", new java.util.TreeSet<>(this.excludedKeys)) + "\n").getBytes(StandardCharsets.UTF_8));
            changed = true;
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
        saveQuietly(true);
        this.archiveLock.writeLock().lock();
        try {
            synchronized (this) {
                if (this.writerArchive != null) {
                    this.writerArchive.release();
                    this.writerArchive = null;
                }
                this.archiveWritesDisabled = true;
            }
            synchronized (this.sessionAdds) {
                this.sessionAdds.values().forEach(MTLRenderPipelineDescriptor::release);
                this.sessionAdds.clear();
            }
        } finally {
            this.archiveLock.writeLock().unlock();
        }
    }
}
