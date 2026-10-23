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
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
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
 * <p>DH's generic objects (clouds, beacon beams: pass {@code distantHorizons:GenericObjectRenderer}) are drawn into the
 * gbuffers with the pack's {@code dh_generic} (Iris's fallback: {@code dh_terrain}), adapted like the terrain
 * ({@link #adaptGeneric}). With a pack {@code dh_shadow} program, DH's terrain is also drawn in the pack's shadow pass
 * ({@link MetalShadowRenderer}): DH's render entry point is called after the shadow pass's opaque terrain
 * ({@link #renderShadowLods}); Iris's DH events would cancel it (they only let DH into Iris's own OpenGL shadow pass),
 * so those two handlers are replaced ({@link DhShadowHooks#hookShadowEvents}), and DH's terrain pass goes into
 * shadowtex0/shadowcolor.</p>
 */
public final class DhIrisBridge {
    static final String TERRAIN_PASS = "distantHorizons:TerrainRenderer";
    static final String GENERIC_PASS = "distantHorizons:GenericObjectRenderer";
    /** {@code -Dnativium.iris.noDhShadow=true}: no LODs in the shadow map. */
    private static final boolean NO_SHADOW = Boolean.getBoolean("nativium.iris.noDhShadow");
    /** {@code -Dnativium.iris.noDhGeneric=true}: DH's clouds/beacons are not drawn while a pack is on (as before). */
    private static final boolean NO_GENERIC = Boolean.getBoolean("nativium.iris.noDhGeneric");

    private final MetalPostPipeline owner;
    private @Nullable GpuTextureView depth;
    private @Nullable GpuTexture scratch;
    private @Nullable GpuTextureView scratchView;
    private boolean announced;
    private boolean drewTerrain;
    private boolean drewShadow;
    private boolean drewGeneric;
    private boolean capture;
    private boolean shadowDisabled;
    /** DH drew its terrain in the current shadow pass. */
    private boolean shadowPassDrawn;

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

    /** True once DH's LODs were drawn into the pack's shadow map ({@code dh_shadow}). */
    public boolean shadowActive() {
        return drewShadow;
    }

    /** True once DH's generic objects (clouds/beacons) were drawn with {@code dh_generic}. */
    public boolean genericActive() {
        return drewGeneric;
    }

    /** DH's generic objects go into the pack's gbuffers with dh_generic. */
    boolean genericEnabled() {
        return !NO_GENERIC && enabled();
    }

    /**
     * LODs in the pack's shadow map: like Iris, when the pack has {@code dh_shadow} and does not set
     * {@code dhShadow.enabled=false}; needs the Metal shadow pass and the replaced DH event handlers.
     */
    boolean shadowEnabled() {
        return !shadowDisabled && enabled() && owner.rendersShadows() && shadowWanted(owner.programSet()) && DhShadowHooks.hookShadowEvents();
    }

    /** dh_shadow could not be compiled: DH's own shader would draw the camera view into the shadow map. */
    void disableShadow() {
        if (!shadowDisabled) {
            shadowDisabled = true;
            NativiumIris.LOGGER.warn("[nativium-iris] Distant Horizons: dh_shadow unavailable on Metal; LODs are left out of the shadow map");
        }
    }

    private static boolean shadowWanted(final ProgramSet programSet) {
        return !NO_SHADOW && programSet.get(ProgramId.DhShadow).isPresent()
                && programSet.getPackDirectives().getShadowDirectives().isDhShadowEnabled().orElse(true);
    }

    /** Startup log line: whether dh_shadow runs. */
    static String shadowStatus(final ProgramSet programSet, final boolean shadowsRun) {
        if (programSet.get(ProgramId.DhShadow).isEmpty()) {
            return "the pack has none (no LOD shadows, like Iris)";
        }
        if (!programSet.getPackDirectives().getShadowDirectives().isDhShadowEnabled().orElse(true)) {
            return "dhShadow.enabled=false in the pack (no LOD shadows, like Iris)";
        }
        if (NO_SHADOW) {
            return "off (-Dnativium.iris.noDhShadow)";
        }
        return shadowsRun ? "LODs drawn into the shadow map" : "not executed (no shadow pass)";
    }

    /**
     * True when the descriptor {@link #retarget} returned last is a pass whose pipelines become the pack's dh_*
     * programs (captured like a vanilla gbuffers pass); false for the scratch passes.
     */
    boolean capturesLast() {
        return capture;
    }

    private static boolean shadowPass() {
        return net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered();
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
        capture = false;
        if (label == null || !label.startsWith("distantHorizons:") || !enabled()) {
            return null;
        }
        if (shadowPass()) {
            // Only reached with dh_shadow (otherwise DH's shadow-pass render is cancelled): the terrain goes into the
            // shadow attachments (GbufferState.descriptor in the shadow pass); the rest of DH's frame (generic objects,
            // fog/SSAO on DH's own textures, copy/apply) is not part of the shadow map.
            if (label.equals(TERRAIN_PASS) && shadowEnabled()) {
                if (!drewShadow) {
                    NativiumIris.LOGGER.info("[nativium-iris] Distant Horizons: LODs drawn with the pack's dh_shadow into its shadow map on Metal");
                }
                drewShadow = true;
                shadowPassDrawn = true;
                capture = true;
                return owner.gbufferDescriptor(descriptor, Optional.empty());
            }
            return label.equals(GENERIC_PASS) || label.contains("Copy") || label.contains("Apply") ? scratchDescriptor(descriptor) : null;
        }
        if (label.equals(GENERIC_PASS)) {
            // DH's clouds/beacon beams: into the gbuffers with DH's depth (dhDepthTex), like Iris's dh_generic
            // framebuffer. Without this they land in DH's colour texture, which is never shown while a pack is on.
            if (!genericEnabled() || descriptor.depthAttachment() == null) {
                return null;
            }
            if (!drewGeneric) {
                NativiumIris.LOGGER.info("[nativium-iris] Distant Horizons: generic objects (clouds/beacons) drawn with the pack's dh_generic on Metal");
            }
            drewGeneric = true;
            capture = true;
            return owner.gbufferDescriptor(descriptor, Optional.empty());
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
            capture = true;
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
        drewShadow = false;
        drewGeneric = false;
        if (shadowOwner == this) {
            shadowOwner = null;
        }
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

    // ---------------------------------------------------------------------------------------------- shadow pass

    /** The bridge whose shadow pass is running ({@link #beginShadow}..{@link #endShadow}); read by the DH events. */
    private static volatile @Nullable DhIrisBridge shadowOwner;

    /** {@link MetalShadowRenderer#renderShadows}: DH may render its terrain into this shadow pass. */
    void beginShadow() {
        shadowPassDrawn = false;
        if (shadowEnabled()) {
            shadowOwner = this;
        }
    }

    /**
     * {@link MetalShadowRenderer}, after the shadow pass's opaque terrain: DH renders from a hook on Sodium's
     * {@code SodiumChunkSection.renderGroup}, which does not fire for the shadow renderer's own call, so DH's
     * render entry point is called directly (once per shadow pass; its passes are then retargeted by {@link #retarget}).
     */
    void renderShadowLods() {
        if (shadowOwner != this || shadowPassDrawn) {
            return;
        }
        try {
            DhShadowHooks.renderLods();
        } catch (RuntimeException | LinkageError e) {
            NativiumIris.LOGGER.warn("[nativium-iris] Distant Horizons: LOD shadow pass failed; LODs are left out of the shadow map", e);
            disableShadow();
        }
    }

    void endShadow() {
        if (shadowOwner != null) {
            shadowOwner = null;
            DhShadowHooks.unbindFrustum();
        }
    }

    /** A dh_shadow shadow pass is running (DH's render-setup event binds Iris's shadow frustum then). */
    static boolean shadowRunning() {
        return shadowPass() && shadowOwner != null;
    }

    /** DH's render must be cancelled in this shadow pass (Iris's rule, unless dh_shadow runs on Metal). */
    static boolean cancelShadowRender() {
        if (!shadowPass()) {
            return false;
        }
        DhIrisBridge bridge = shadowOwner;
        return bridge == null || !bridge.shadowEnabled();
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

    private static final Pattern GENERIC_INPUT = Pattern.compile(
            "^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+\\w+\\s+(?:vPosition|iris_color|aScale|aTranslateChunk|aTranslateSubChunk|aMaterial)\\s*;\\s*$", Pattern.MULTILINE);
    private static final Pattern GENERIC_LOOSE = Pattern.compile(
            "^\\s*uniform\\s+\\w+\\s+(?:uOffsetChunk|uOffsetSubChunk|uCameraPosChunk|uCameraPosSubChunk|uSkyLight|uBlockLight)\\s*;\\s*$", Pattern.MULTILINE);

    /**
     * Iris's DH generic patch ({@code DHGenericTransformer}) expects DH's OpenGL instanced box layout (per-instance
     * {@code aScale}/{@code aTranslateChunk}/{@code aTranslateSubChunk}, {@code iris_color}, {@code int aMaterial})
     * and loose box-group uniforms. DH's Blaze3D generic pipeline draws the boxes unscaled and untranslated per
     * group ({@code vPosition}, {@code aColor}, {@code uint aMaterial}, 24 vertices per box, not instanced) with the
     * group data in {@code vertUniformBlock}. {@code gl_VertexID} is taken modulo 24 like DH's own shader (the face
     * normal is picked by vertex index).
     */
    static String adaptGeneric(final String vertex) {
        String body = GENERIC_INPUT.matcher(vertex).replaceAll("");
        body = GENERIC_LOOSE.matcher(body).replaceAll("");
        body = body.replaceAll("\\bgl_VertexID\\b", "(gl_VertexID % 24)");
        body = body.replaceAll("\\baMaterial\\b", "iris_dhMaterial");
        String glue = """
                layout(location = 0) in vec3 vPosition;
                layout(location = 1) in vec4 aColor;
                layout(location = 2) in uint aMaterial;
                layout(std140) uniform vertUniformBlock {
                    ivec3 uOffsetChunk; vec3 uOffsetSubChunk; ivec3 uCameraPosChunk; vec3 uCameraPosSubChunk;
                    mat4 uProjectionMvm; int uSkyLight; int uBlockLight;
                    float uNorthShading; float uSouthShading; float uEastShading; float uWestShading; float uTopShading; float uBottomShading;
                };
                #define iris_color aColor
                #define aScale vec3(1.0)
                #define aTranslateChunk ivec3(0)
                #define aTranslateSubChunk vec3(0.0)
                #define iris_dhMaterial int(aMaterial)
                """;
        Matcher m = VERSION_BLOCK.matcher(body);
        if (m.find()) {
            return body.substring(0, m.end()) + glue + body.substring(m.end());
        }
        return glue + body;
    }
}
