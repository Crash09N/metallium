/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import de.nprmedia.nativium.iris.NativiumIris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distant Horizons' LODs under a shader pack on Metal (docs/design/iris-voxy-dh.md, section 4). On Metal DH runs its
 * Blaze3D (renderpearl) renderer, for which Iris has no hooks (Iris overrides DH's OpenGL framebuffer and programs
 * through the DH API). Here the Blaze3D passes are redirected instead, like the vanilla/Sodium passes in
 * {@link GbufferPasses}:
 * <ul>
 *   <li>DH's terrain pass ({@code distantHorizons:TerrainRenderer}) draws into the pack's gbuffers with DH's own depth
 *       buffer, which becomes {@code dhDepthTex0/1} for the pack's passes;</li>
 *   <li>its terrain pipelines run the pack's {@code dh_terrain}/{@code dh_water}, patched by Iris's own
 *       {@code TransformPatcher.patchDHTerrain} and adapted to the Blaze3D vertex format and DH's uniform blocks
 *       ({@link #adaptVertex});</li>
 *   <li>DH's copy/apply passes (DH's colour onto the main target) draw into a scratch texture: the pack composites
 *       the LODs itself.</li>
 * </ul>
 * Not covered yet: {@code dh_shadow} (LOD shadows), {@code dh_generic} (DH clouds/beacons).
 */
public final class DhIrisBridge {
    static final String TERRAIN_PASS = "distantHorizons:TerrainRenderer";

    private final MetalPostPipeline owner;
    private @Nullable GpuTextureView depth;
    private @Nullable GpuTexture scratch;
    private @Nullable GpuTextureView scratchView;
    private boolean announced;
    private boolean drewTerrain;

    DhIrisBridge(final MetalPostPipeline owner) {
        this.owner = owner;
    }

    static boolean installed() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
    }

    /** The pack has a {@code dh_terrain} program, DH renders LODs and the pack's gbuffers run on Metal. */
    boolean enabled() {
        if (!installed() || !owner.rendersGbuffers() || owner.programSet().get(ProgramId.DhTerrain).isEmpty()) {
            return false;
        }
        try {
            return DHCompat.hasRenderingEnabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** True once DH's terrain was drawn into the pack's gbuffers (status line, game test). */
    public boolean active() {
        return drewTerrain;
    }

    /**
     * {@link GbufferPasses#retarget} for DH's own render passes; null if {@code descriptor} is not one of them (or the
     * bridge is off).
     */
    @Nullable RenderPassDescriptor retarget(final RenderPassDescriptor descriptor) {
        String label;
        try {
            label = descriptor.label().get();
        } catch (RuntimeException e) {
            return null;
        }
        if (label == null || !label.startsWith("distantHorizons:") || !enabled()) {
            return null;
        }
        if (label.equals(TERRAIN_PASS)) {
            if (descriptor.depthAttachment() == null) {
                return null;
            }
            depth = descriptor.depthAttachment().textureView();
            drewTerrain = true;
            if (!announced) {
                announced = true;
                NativiumIris.LOGGER.info("[nativium-iris] Distant Horizons: LOD terrain drawn with the pack's dh_terrain into its gbuffers on Metal");
            }
            // No colour clear: the gbuffers already hold the vanilla/Sodium scene of this frame.
            return owner.gbufferDescriptor(descriptor, Optional.empty());
        }
        if (label.contains("Copy") || label.contains("Apply")) {
            return scratchDescriptor(descriptor);
        }
        return null;
    }

    /** DH's colour copy/apply: same pass, written to a scratch colour texture instead of the main target. */
    private RenderPassDescriptor scratchDescriptor(final RenderPassDescriptor descriptor) {
        RenderPassDescriptor.Attachment<Optional<org.joml.Vector4fc>> first = descriptor.colorAttachments().getFirst();
        GpuTexture source = first.textureView().texture();
        if (scratch == null || scratch.getWidth(0) != source.getWidth(0) || scratch.getHeight(0) != source.getHeight(0)
                || scratch.getFormat() != source.getFormat()) {
            closeScratch();
            scratch = RenderSystem.getDevice().createTexture("nativium-iris dh scratch", GpuTexture.USAGE_RENDER_ATTACHMENT,
                    source.getFormat(), source.getWidth(0), source.getHeight(0), 1, 1);
            scratchView = RenderSystem.getDevice().createTextureView(scratch);
        }
        RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(descriptor.label()).withColorAttachment(scratchView, Optional.empty());
        if (descriptor.depthAttachment() != null) {
            builder.withDepthAttachment(descriptor.depthAttachment().textureView(), descriptor.depthAttachment().clearValue());
        }
        return builder.withRenderArea(descriptor.renderArea()).build();
    }

    /** {@code dhDepthTex0/1}: DH's LOD depth buffer, or null (white = depth 1, "no LOD here"). */
    @Nullable GpuTextureView depthView() {
        return drewTerrain && depth != null && !depth.isClosed() ? depth : null;
    }

    void close() {
        closeScratch();
        depth = null;
        drewTerrain = false;
    }

    private void closeScratch() {
        if (scratchView != null) {
            scratchView.close();
            scratchView = null;
        }
        if (scratch != null) {
            scratch.close();
            scratch = null;
        }
    }

    // ---------------------------------------------------------------------------------------------- program

    /** DH's near clip distance in blocks ({@code clipDistance}, like Iris's IrisLodRenderProgram). */
    static float nearClipDistance() {
        try {
            float partialTicks = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            return com.seibel.distanthorizons.api.DhApi.Delayed.renderProxy.getNearClipPlaneDistanceInBlocks(partialTicks);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static final Pattern IRIS_INPUT = Pattern.compile("^\\s*in\\s+(?:uvec4|vec4)\\s+(?:vPosition|iris_color|irisExtra)\\s*;\\s*$", Pattern.MULTILINE);
    private static final Pattern IRIS_LOOSE = Pattern.compile("^\\s*uniform\\s+(?:vec3|float)\\s+(?:modelOffset|mircoOffset|worldYOffset)\\s*;\\s*$", Pattern.MULTILINE);
    private static final Pattern VERSION_BLOCK = Pattern.compile("\\A(?:\\s*#(?:version|extension)[^\\n]*\\n)+");

    /**
     * Iris's DH terrain patch expects DH's OpenGL vertex layout ({@code uvec4 vPosition} = position + meta,
     * {@code vec4 iris_color}, {@code uvec4 irisExtra} = material, normal, texture tile low/high byte) and loose
     * {@code modelOffset}/{@code mircoOffset} uniforms. DH's Blaze3D pipeline delivers the same data as six attributes
     * and two uniform blocks; this maps one onto the other (declarations replaced, macros for the uses).
     */
    static String adaptVertex(final String vertex) {
        String body = IRIS_INPUT.matcher(vertex).replaceAll("");
        body = IRIS_LOOSE.matcher(body).replaceAll("");
        body = body.replaceAll("\\bvPosition\\b", "iris_dhPosition");
        String glue = """
                layout(location = 0) in uvec3 vPosition;
                layout(location = 1) in uint meta;
                layout(location = 2) in vec4 vColor;
                layout(location = 3) in uint irisMaterial;
                layout(location = 4) in uint irisNormal;
                layout(location = 5) in uint textureTile;
                layout(std140) uniform vertUniqueUniformBlock { vec3 uModelOffset; };
                layout(std140) uniform vertSharedUniformBlock {
                    bool uIsWhiteWorld; float uWorldYOffset; float uMircoOffset; float uEarthRadius;
                    float uFrameMod8; float uViewWidth; float uViewHeight; vec3 uCameraPos; mat4 uCombinedMatrix;
                };
                #define iris_dhPosition uvec4(vPosition, meta)
                #define iris_color vColor
                #define irisExtra uvec4(irisMaterial, irisNormal, textureTile & 0xFFu, (textureTile >> 8u) & 0xFFu)
                #define modelOffset (uModelOffset - uCameraPos + vec3(0.0, uWorldYOffset, 0.0))
                #define mircoOffset uMircoOffset
                """;
        Matcher m = VERSION_BLOCK.matcher(body);
        if (m.find()) {
            return body.substring(0, m.end()) + glue + body.substring(m.end());
        }
        return glue + body;
    }
}
