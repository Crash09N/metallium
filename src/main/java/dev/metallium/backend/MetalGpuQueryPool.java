/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.util.OptionalLong;

@Environment(EnvType.CLIENT)
final class MetalGpuQueryPool implements GpuQueryPool {
    private final OptionalLong[] values;

    MetalGpuQueryPool(final int size) {
        this.values = new OptionalLong[size];

        for (int i = 0; i < size; i++) {
            this.values[i] = OptionalLong.empty();
        }
    }

    void setValue(final int index, final long value) {
        this.values[index] = OptionalLong.of(value);
    }

    @Override
    public int size() {
        return this.values.length;
    }

    @Override
    public @NonNull OptionalLong getValue(final int index) {
        return this.values[index];
    }

    @Override
    public OptionalLong @NonNull [] getValues(final int index, final int count) {
        OptionalLong[] result = new OptionalLong[count];
        System.arraycopy(this.values, index, result, 0, count);
        return result;
    }

    @Override
    public void close() {
        for (int i = 0; i < this.values.length; i++) {
            this.values[i] = OptionalLong.empty();
        }
    }
}
