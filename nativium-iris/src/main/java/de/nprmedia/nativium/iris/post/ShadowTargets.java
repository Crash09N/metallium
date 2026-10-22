/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import de.nprmedia.nativium.backend.MetalDepthCompareSamplers;
import de.nprmedia.nativium.iris.mixin.FrontendGpuDeviceAccessor;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import org.jspecify.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Iris's {@code ShadowRenderTargets} on Metal: {@code shadowtex0} (all shadow casters), {@code shadowtex1} (copy taken
 * before translucent casters), both {@code D32_FLOAT} at {@code shadowMapResolution}, and {@code shadowcolor0..7} (main
 * / alt pairs, formats and clear colours from the pack's {@code shadowcolorN*} directives). Like Iris
 * (LGPL-3.0, whose behaviour this follows), colour buffers are only created once a program uses them.
 *
 * <p>Samplers follow Iris's {@code ShadowRenderTargets.getSamplerFor}: a depth-compare sampler ({@code LEQUAL}) for
 * {@code sampler2DShadow} uses, linear unless the pack asks for {@code shadowtexNNearest}.
 */
final class ShadowTargets implements AutoCloseable {
    static final int COLOR_COUNT = PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS;

    private final PackShadowDirectives directives;
    final int resolution;
    final GpuTexture depth;
    final GpuTextureView depthView;
    final GpuTexture depthNoTranslucents;
    final GpuTextureView depthNoTranslucentsView;
    private final PostTargets.Target[] colors = new PostTargets.Target[COLOR_COUNT];
    private final GpuSampler[] depthSamplers = new GpuSampler[2];
    private final GpuSampler[] depthCompareSamplers = new GpuSampler[2];
    private final GpuSampler linear;
    private final GpuSampler nearest;
    /** shadowcolor buffers flipped by shadowcomp passes (read from alt afterwards). */
    private com.google.common.collect.ImmutableSet<Integer> flipped = com.google.common.collect.ImmutableSet.of();
    private boolean everCleared;
    /** Stage 5: shadowcolor buffers bound as {@code shadowcolorimgN} by compute passes. */
    private final java.util.BitSet storage = new java.util.BitSet();

    ShadowTargets(final PackShadowDirectives directives) {
        this.directives = directives;
        this.resolution = Math.max(1, directives.getResolution());
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;
        this.depth = device.createTexture("iris shadowtex0", usage, GpuFormat.D32_FLOAT, resolution, resolution, 1, 1);
        this.depthView = device.createTextureView(depth);
        this.depthNoTranslucents = device.createTexture("iris shadowtex1", usage, GpuFormat.D32_FLOAT, resolution, resolution, 1, 1);
        this.depthNoTranslucentsView = device.createTextureView(depthNoTranslucents);
        var backend = ((FrontendGpuDeviceAccessor) device).nativium$getBackend();
        for (int i = 0; i < 2; i++) {
            PackShadowDirectives.DepthSamplingSettings settings = directives.getDepthSamplingSettings().get(i);
            FilterMode filter = settings.getNearest() ? FilterMode.NEAREST : FilterMode.LINEAR;
            depthSamplers[i] = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, filter, filter, 1, OptionalDouble.empty());
            depthCompareSamplers[i] = MetalDepthCompareSamplers.supported(backend)
                    ? MetalDepthCompareSamplers.create(backend, AddressMode.CLAMP_TO_EDGE, filter, CompareOp.LESS_THAN_OR_EQUAL)
                    : depthSamplers[i];
        }
        this.linear = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        this.nearest = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        clearDepth();
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.copyTextureToTexture(depth, depthNoTranslucents, 0, 0, 0, 0, 0, resolution, resolution);
    }

    PackShadowDirectives.SamplingSettings colorSettings(final int index) {
        PackShadowDirectives.SamplingSettings settings = directives.getColorSamplingSettings().get(index);
        return settings != null ? settings : new PackShadowDirectives.SamplingSettings();
    }

    GpuFormat colorFormat(final int index) {
        return PostTargets.toGpuFormat(colorSettings(index).getFormat());
    }

    /** {@code shadowcolorN}, created (and cleared) on first use; never call inside a render pass. */
    PostTargets.Target color(final int index) {
        PostTargets.Target target = colors[index];
        if (target == null) {
            target = PostTargets.create("iris shadowcolor" + index, colorFormat(index), resolution, resolution, storage.get(index), false);
            colors[index] = target;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            org.joml.Vector4f clear = colorSettings(index).getClearColor();
            encoder.clearColorTexture(target.main, new org.joml.Vector4f(clear));
            encoder.clearColorTexture(target.alt, new org.joml.Vector4f(clear));
        }
        return target;
    }

    /** Must be called before the buffer is first used. */
    void requireStorage(final int index) {
        if (index >= 0 && index < COLOR_COUNT) {
            storage.set(index);
        }
    }

    /** Creates every shadowcolor buffer used as an image; must run outside render passes. */
    void createStorageTargets() {
        for (int i = storage.nextSetBit(0); i >= 0; i = storage.nextSetBit(i + 1)) {
            color(i);
        }
    }

    com.google.common.collect.ImmutableSet<Integer> flipped() {
        return flipped;
    }

    PostTargets.@Nullable Target colorIfPresent(final int index) {
        return index >= 0 && index < COLOR_COUNT ? colors[index] : null;
    }

    /** Iris's per-frame shadow clear: depth to 1.0 (forward Z), colour buffers with clearing enabled to their colour. */
    void beginFrame() {
        clearDepth();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int i = 0; i < COLOR_COUNT; i++) {
            if (colors[i] != null && (colorSettings(i).getClear() || !everCleared)) {
                org.joml.Vector4f clear = new org.joml.Vector4f(colorSettings(i).getClearColor());
                encoder.clearColorTexture(colors[i].main, clear);
                encoder.clearColorTexture(colors[i].alt, clear);
            }
        }
        everCleared = true;
        flipped = com.google.common.collect.ImmutableSet.of();
    }

    private void clearDepth() {
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(depth, 1.0);
    }

    /** {@code shadowtex1}: the shadow depth before translucent casters (Iris's copyPreTranslucentDepth). */
    void copyPreTranslucentDepth() {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, depthNoTranslucents, 0, 0, 0, 0, 0, resolution, resolution);
    }

    void setFlipped(final com.google.common.collect.ImmutableSet<Integer> flipped) {
        this.flipped = flipped;
    }

    boolean isFlipped(final int index) {
        return flipped.contains(index);
    }

    /** Sampler for {@code shadowtex0} (0) or {@code shadowtex1} (1); {@code compare} for {@code sampler2DShadow}. */
    GpuSampler depthSampler(final int index, final boolean compare) {
        return compare ? depthCompareSamplers[index] : depthSamplers[index];
    }

    GpuSampler colorSampler(final int index) {
        return colorSettings(index).getNearest() ? nearest : linear;
    }

    @Override
    public void close() {
        for (int i = 0; i < COLOR_COUNT; i++) {
            if (colors[i] != null) {
                colors[i].close();
                colors[i] = null;
            }
        }
        for (int i = 0; i < 2; i++) {
            if (depthCompareSamplers[i] != depthSamplers[i]) {
                depthCompareSamplers[i].close();
            }
            depthSamplers[i].close();
        }
        linear.close();
        nearest.close();
        depthView.close();
        depth.close();
        depthNoTranslucentsView.close();
        depthNoTranslucents.close();
    }
}
