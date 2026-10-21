/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package de.nprmedia.nativium.backend;

import de.nprmedia.nativium.Nativium;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final List<Runnable>[] queues;
    private int currentQueueIndex;

    @SuppressWarnings("unchecked")
    MetalDestructionQueue(final int queueCount) {
        this.queues = (List<Runnable>[]) new List<?>[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ArrayList<>();
        }
    }

    void add(final Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        this.queues[this.currentQueueIndex].add(destroyAction);
    }

    void rotate() {
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.queues.length;
        List<Runnable> toDestroy = this.queues[this.currentQueueIndex];
        this.queues[this.currentQueueIndex] = new ArrayList<>();
        for (Runnable destroyAction : toDestroy) {
            try {
                destroyAction.run();
            } catch (Exception e) {
                Nativium.LOGGER.error("[nativium] Destroy action threw an exception; resource may have leaked", e);
            }
        }
    }

    void close() {
        for (int i = 0; i < this.queues.length; i++) {
            this.rotate();
        }
    }
}
