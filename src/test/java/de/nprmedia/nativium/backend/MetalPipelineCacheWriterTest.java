/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The binary archive writer's crash marker and its wait for pipeline creation to settle (no Metal needed). */
class MetalPipelineCacheWriterTest {
    @Test
    void markerPresentOnlyWhileSerializing(@TempDir final Path directory) {
        assertFalse(MetalPipelineCache.serializeCrashed(directory));
        assertTrue(MetalPipelineCache.beginSerialize(directory));
        // A process that dies here (native crash in serializeToURL) leaves the marker behind ...
        assertTrue(MetalPipelineCache.serializeCrashed(directory));
        // ... a normal return removes it.
        MetalPipelineCache.endSerialize(directory);
        assertFalse(MetalPipelineCache.serializeCrashed(directory));
    }

    @Test
    void leftoverMarkerMeansAnEarlierSessionCrashedWhileWriting(@TempDir final Path directory) throws Exception {
        Files.writeString(directory.resolve(MetalPipelineCache.SERIALIZE_MARKER), "serializing since earlier");
        assertTrue(MetalPipelineCache.serializeCrashed(directory));
    }

    @Test
    void markerCannotBeWrittenIntoMissingDirectory(@TempDir final Path directory) {
        assertFalse(MetalPipelineCache.beginSerialize(directory.resolve("missing")));
    }

    @Test
    void writerWaitsForPipelineCreationToSettle() {
        long now = 1_000_000_000_000L;
        long justAdded = now - MetalPipelineCache.QUIET_NANOS / 2;
        long quietAgo = now - MetalPipelineCache.QUIET_NANOS;
        assertFalse(MetalPipelineCache.quietEnough(now, justAdded, 0, false));
        assertTrue(MetalPipelineCache.quietEnough(now, quietAgo, 0, false));
        // On close it writes at once.
        assertTrue(MetalPipelineCache.quietEnough(now, now, 0, true));
        // Never put off for longer than MAX_DEFER_NANOS, even while pipelines keep coming.
        assertFalse(MetalPipelineCache.quietEnough(now, now, now - MetalPipelineCache.MAX_DEFER_NANOS + 1, false));
        assertTrue(MetalPipelineCache.quietEnough(now, now, now - MetalPipelineCache.MAX_DEFER_NANOS, false));
    }
}
