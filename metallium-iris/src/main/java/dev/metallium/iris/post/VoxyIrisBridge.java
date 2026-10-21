package dev.metallium.iris.post;

import com.google.common.collect.ImmutableSet;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.metallium.iris.MetalliumIris;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Voxy's far terrain under a shader pack on Metal (docs/design/iris-voxy-dh.md). Voxy's own shader-pack pipeline
 * ({@code IrisVoxyRenderPipeline}, running through metallium-gl) draws the LODs with the pack's {@code voxy_*.glsl}
 * code into the pack's gbuffers; this bridge gives it what Iris's OpenGL pipeline would: its pipeline data, built by
 * Voxy's own {@code IrisVoxyRenderPipelineData.buildPipeline} against a {@link VoxyIrisFacade}, with GL names
 * (metallium-gl) of {@link MetalPostPipeline}'s Metal textures. In the other direction it exposes Voxy's depth buffers
 * as {@code vxDepthTexOpaque/Trans} to the pack's Metal passes. Voxy is only referenced by name (reflection).
 */
public final class VoxyIrisBridge {
    private static final String DATA = "me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData";
    private static final String PIPELINE = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline";
    private static final String ABSTRACT_PIPELINE = "me.cortex.voxy.client.core.AbstractRenderPipeline";
    private static final String PATCH_HOLDER = "me.cortex.voxy.client.iris.IGetVoxyPatchData";
    private static final String PATCH = "me.cortex.voxy.client.iris.IrisShaderPatch";
    private static final String DEPTH_FB = "me.cortex.voxy.client.core.rendering.util.DepthFramebuffer";
    private static final String GL_TEXTURE = "me.cortex.voxy.client.core.gl.GlTexture";
    private static final String IRIS_UTIL = "me.cortex.voxy.client.core.util.IrisUtil";
    private static final String CAPTURED = "me.cortex.voxy.client.core.util.IrisUtil$CapturedViewportParameters";
    private static final String HOLDER = "me.cortex.voxy.client.core.IVoxyRenderSystemHolder";
    private static final String RENDER_SYSTEM = "me.cortex.voxy.client.core.VoxyRenderSystem";

    private static @Nullable Reflection reflection;
    private static boolean reflectionFailed;
    private static volatile @Nullable String lastError;

    private final MetalPostPipeline owner;
    /** Pipeline data of the Voxy pipeline created last (Voxy binds one pipeline to it); null before / after failure. */
    private @Nullable Object data;
    private @Nullable Object patch;
    private Map<String, String> declaredSamplers = Map.of();
    /** Colour buffers handed to Voxy as stable GL names (index, alt) - re-pointed every frame. */
    private final Set<Integer> stableBuffers = new LinkedHashSet<>();
    private final Set<String> warned = new LinkedHashSet<>();
    private @Nullable GlSampler nearest;
    private @Nullable GlSampler linear;
    private @Nullable GlSampler linearRepeat;
    private @Nullable GlSampler compare;
    private final DepthWrapper opaqueDepth = new DepthWrapper("voxy vxDepthTexOpaque");
    private final DepthWrapper translucentDepth = new DepthWrapper("voxy vxDepthTexTrans");

    VoxyIrisBridge(final MetalPostPipeline owner) {
        this.owner = owner;
    }

    // ---------------------------------------------------------------------------------------------- creation

    /**
     * Voxy's {@code RenderPipelineFactory.createIrisPipeline} on Metal: an {@code IrisVoxyRenderPipeline} for the active
     * Metal shader pack pipeline, or null (Voxy then uses its normal pipeline; the core safety net pauses it).
     */
    public static @Nullable Object createIrisPipeline(final Object properties, final Object nodeManager, final Object nodeCleaner,
                                                      final Object traversal, final BooleanSupplier frexStillHasWork) {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof MetalPostPipeline pipeline) || !pipeline.rendersGbuffers()
                || !dev.metallium.compat.voxy.VoxyCompat.isRoutedThroughMetalliumGl()) {
            return null;
        }
        return pipeline.voxyBridge().create(properties, nodeManager, nodeCleaner, traversal, frexStillHasWork);
    }

    private @Nullable Object create(final Object properties, final Object nodeManager, final Object nodeCleaner,
                                    final Object traversal, final BooleanSupplier frexStillHasWork) {
        Reflection r = reflection();
        if (r == null) {
            return null;
        }
        try {
            Object patch = r.programSet.isInstance(owner.programSet()) ? r.getPatchData.invoke(owner.programSet()) : null;
            if (patch == null) {
                MetalliumIris.LOGGER.info("[metallium-iris] Voxy: the shader pack has no voxy.json; Voxy uses its normal pipeline");
                return null;
            }
            this.patch = patch;
            @SuppressWarnings("unchecked")
            Map<String, String> samplers = (Map<String, String>) r.getSamplerSet.invoke(patch);
            this.declaredSamplers = samplers != null ? Map.copyOf(samplers) : Map.of();
            VoxyIrisFacade facade = (VoxyIrisFacade) UNSAFE.allocateInstance(VoxyIrisFacade.class);
            facade.bridge = this;
            UNSAFE.putObject(facade, UNSAFE.objectFieldOffset(IrisRenderingPipeline.class.getDeclaredField("renderTargets")),
                    renderTargets((int[]) r.getOpaqueTargets.invoke(patch), (int[]) r.getTranslucentTargets.invoke(patch)));
            Object built = r.buildPipeline.invoke(null, facade, patch, owner.customUniforms(), null);
            Object pipeline = r.pipelineConstructor.newInstance(properties, built, nodeManager, nodeCleaner, traversal, frexStillHasWork);
            this.data = built;
            lastError = null;
            MetalliumIris.LOGGER.info("[metallium-iris] Voxy: shader-pack LOD pipeline created on Metal (draw buffers {} / {}, samplers {})",
                    java.util.Arrays.toString((int[]) r.getOpaqueTargets.invoke(patch)), java.util.Arrays.toString((int[]) r.getTranslucentTargets.invoke(patch)),
                    declaredSamplers.keySet());
            return pipeline;
        } catch (InvocationTargetException e) {
            return failed(e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return failed(e);
        }
    }

    private @Nullable Object failed(final Throwable e) {
        data = null;
        lastError = e.toString();
        MetalliumIris.LOGGER.error("[metallium-iris] Voxy: could not create its shader-pack pipeline on Metal; Voxy's LODs stay paused", e);
        return null;
    }

    /** Iris's {@code RenderTargets} stand-in for Voxy's {@code getDrawBuffers}: the pack's draw buffers as stable GL names. */
    private RenderTargets renderTargets(final int[] opaque, final int[] translucent) throws InstantiationException, NoSuchFieldException {
        RenderTarget[] array = new RenderTarget[PostTargets.COUNT];
        for (int[] list : new int[][]{opaque, translucent}) {
            for (int index : list) {
                if (index < 0 || index >= PostTargets.COUNT || array[index] != null) {
                    continue;
                }
                stableBuffers.add(index);
                RenderTarget target = (RenderTarget) UNSAFE.allocateInstance(RenderTarget.class);
                UNSAFE.putInt(target, UNSAFE.objectFieldOffset(RenderTarget.class.getDeclaredField("mainTexture")), stableName(index, false));
                UNSAFE.putInt(target, UNSAFE.objectFieldOffset(RenderTarget.class.getDeclaredField("altTexture")), stableName(index, true));
                UNSAFE.putBoolean(target, UNSAFE.objectFieldOffset(RenderTarget.class.getDeclaredField("isValid")), true);
                array[index] = target;
            }
        }
        RenderTargets targets = (RenderTargets) UNSAFE.allocateInstance(RenderTargets.class);
        UNSAFE.putObject(targets, UNSAFE.objectFieldOffset(RenderTargets.class.getDeclaredField("targets")), array);
        return targets;
    }

    private int stableName(final int index, final boolean alt) {
        return dev.metallium.gl.MetalGLInterop.stableTextureName("metallium-iris colortex" + index + (alt ? " alt" : ""), owner.targets().get(index).texture(alt));
    }

    // ---------------------------------------------------------------------------------------------- samplers

    /** What {@code IrisRenderingPipeline.addGbufferOrShadowSamplers} adds, for the samplers the pack's Voxy patch declares. */
    void addSamplers(final SamplerHolder holder, final boolean hasTexture, final boolean hasLightmap, final boolean hasOverlay) {
        for (Map.Entry<String, String> entry : declaredSamplers.entrySet()) {
            String name = entry.getKey();
            switch (name) {
                case "tex", "texture", "gtexture", "u_MainSampler" -> {
                    if (hasTexture) {
                        holder.addExternalSampler(IrisSamplers.ALBEDO_TEXTURE_UNIT, name);
                        continue;
                    }
                }
                case "lightmap" -> {
                    if (hasLightmap) {
                        holder.addExternalSampler(IrisSamplers.LIGHTMAP_TEXTURE_UNIT, name);
                        continue;
                    }
                }
                case "iris_overlay" -> {
                    if (hasOverlay) {
                        holder.addExternalSampler(IrisSamplers.OVERLAY_TEXTURE_UNIT, name);
                        continue;
                    }
                }
                default -> {
                }
            }
            boolean hardware = entry.getValue() != null && entry.getValue().contains("Shadow") || name.endsWith("HW");
            IntSupplier texture = () -> textureName(name);
            Supplier<GlSampler> sampler = sampler(name, hardware);
            holder.addDynamicSampler(TextureType.TEXTURE_2D, texture, null, sampler, name);
        }
    }

    /** GL name of the texture a gbuffers program reads as {@code name} (Iris semantics), looked up at each bind. */
    private int textureName(final String name) {
        if (name.equals("vxDepthTexOpaque") || name.equals("vxDepthTexTrans")) {
            return voxyDepthName(name.equals("vxDepthTexOpaque"));
        }
        Object texture = texture(name);
        return texture == null ? 0 : dev.metallium.gl.MetalGLInterop.glId(texture);
    }

    private @Nullable Object texture(final String name) {
        CustomTextures.Texture custom = owner.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name, Set.of());
        if (custom != null) {
            return custom.view();
        }
        int colortex = MetalPostPipeline.colortexIndex(name);
        if (colortex >= 0) {
            PostTargets.Target target = owner.targets().getIfPresent(colortex);
            return target == null ? owner.whiteView() : target.view(opaqueWritesAlt().contains(colortex));
        }
        ShadowTargets shadow = owner.shadowTargets();
        switch (name) {
            case "depthtex0", "gdepthtex":
                return Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
            case "depthtex1":
                return owner.depthNoTranslucents();
            case "depthtex2":
                return owner.depthNoHand();
            case "noisetex":
                return owner.noiseView();
            case "shadowtex0", "shadowtex0HW", "watershadow":
                return shadow.depthView;
            case "shadowtex1", "shadowtex1HW":
                return shadow.depthNoTranslucentsView;
            case "shadow":
                return declaredSamplers.containsKey("watershadow") ? shadow.depthNoTranslucentsView : shadow.depthView;
            default:
                break;
        }
        int color = name.equals("shadowcolor") ? 0 : name.startsWith("shadowcolor") ? parse(name.substring("shadowcolor".length())) : -1;
        if (color >= 0 && color < ShadowTargets.COLOR_COUNT) {
            PostTargets.Target target = shadow.colorIfPresent(color);
            return target == null ? owner.whiteView() : target.view(shadow.isFlipped(color));
        }
        if (!name.startsWith("dhDepthTex") && warned.add(name)) {
            MetalliumIris.LOGGER.warn("[metallium-iris] Voxy: sampler '{}' of the pack's Voxy patch has no source on Metal; white is bound", name);
        }
        return owner.whiteView();
    }

    private Supplier<GlSampler> sampler(final String name, final boolean hardware) {
        if (hardware) {
            return () -> compare != null ? compare : (compare = new GlSampler(dev.metallium.gl.MetalGLInterop.sampler(true, false, true)));
        }
        int colortex = MetalPostPipeline.colortexIndex(name);
        boolean integer = colortex >= 0 && owner.targets().integer(colortex);
        boolean depth = name.startsWith("depthtex") || name.equals("gdepthtex") || name.startsWith("vxDepthTex") || name.startsWith("dhDepthTex")
                || name.startsWith("shadowtex") || name.equals("shadow") || name.equals("watershadow");
        if (integer || depth) {
            return () -> nearest != null ? nearest : (nearest = new GlSampler(dev.metallium.gl.MetalGLInterop.sampler(false, false, false)));
        }
        if (name.equals("noisetex") || owner.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name, Set.of()) != null) {
            return () -> linearRepeat != null ? linearRepeat : (linearRepeat = new GlSampler(dev.metallium.gl.MetalGLInterop.sampler(true, true, false)));
        }
        return () -> linear != null ? linear : (linear = new GlSampler(dev.metallium.gl.MetalGLInterop.sampler(true, false, false)));
    }

    private static int parse(final String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------------------------------- per frame

    /** What the opaque gbuffers (and Voxy's LODs) write: the flip state after the prepare passes. */
    ImmutableSet<Integer> opaqueWritesAlt() {
        return owner.sceneWritesAlt();
    }

    /** True while a Voxy shader-pack pipeline created by this bridge is alive (bound to its data). */
    public boolean active() {
        Reflection r = reflection;
        try {
            return data != null && r != null && r.thePipeline.get(data) != null;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    /** Start of a level frame: re-point the draw-buffer names (resizes) and set Voxy's viewport as Voxy's Iris mixin does. */
    void beginFrame() {
        if (!active()) {
            return;
        }
        for (int index : stableBuffers) {
            stableName(index, false);
            stableName(index, true);
        }
        Reflection r = reflection;
        try {
            Object captured = r.capturedViewport.get(null);
            if (captured != null) {
                Object renderSystem = r.renderSystem.invoke(null);
                if (renderSystem != null) {
                    r.applyViewport.invoke(captured, renderSystem);
                    r.capturedViewport.set(null, null);
                    r.usedIrisViewport.setBoolean(null, true);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (warned.add("viewport")) {
                MetalliumIris.LOGGER.warn("[metallium-iris] Voxy: could not apply the captured viewport: {}", e.toString());
            }
        }
    }

    /** GL name of Voxy's opaque / translucent LOD depth buffer, 0 if none. */
    private int voxyDepthName(final boolean opaque) {
        Reflection r = reflection;
        if (data == null || r == null) {
            return 0;
        }
        try {
            Object pipeline = r.thePipeline.get(data);
            if (pipeline == null) {
                return 0;
            }
            Object framebuffer = (opaque ? r.fb : r.fbTranslucent).get(pipeline);
            Object texture = framebuffer == null ? null : r.getDepthTex.invoke(framebuffer);
            return texture == null ? 0 : r.glTextureId.getInt(texture);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * {@code vxDepthTexOpaque} / {@code vxDepthTexTrans} for the pack's Metal passes: Voxy's depth buffer, or null
     * (the caller binds white = depth 1, "no LOD here").
     */
    @Nullable GpuTextureView voxyDepthView(final boolean opaque) {
        int name = voxyDepthName(opaque);
        if (name == 0) {
            return null;
        }
        return (opaque ? opaqueDepth : translucentDepth).view(dev.metallium.gl.MetalGLInterop.metalTexture(name));
    }

    void close() {
        opaqueDepth.close();
        translucentDepth.close();
    }

    /** Status line / test: null if fine, else why Voxy's shader-pack pipeline could not be created. */
    public static @Nullable String lastError() {
        return lastError;
    }

    /** A renderpearl wrapper of one of Voxy's Metal depth textures, re-made when Voxy re-creates it. */
    private static final class DepthWrapper {
        private final String label;
        private long handle;
        private @Nullable GpuTexture texture;
        private @Nullable GpuTextureView view;

        DepthWrapper(final String label) {
            this.label = label;
        }

        @Nullable GpuTextureView view(final io.github.kokodio.metaljvm.metal.@Nullable MTLTexture metal) {
            if (metal == null) {
                return null;
            }
            if (metal.handle() != handle || view == null) {
                close();
                metal.retain(); // the wrapper owns one reference
                GpuFormat format = metal.pixelFormat() == io.github.kokodio.metaljvm.metal.MTLPixelFormat.Depth32Float ? GpuFormat.D32_FLOAT : GpuFormat.D32_FLOAT_S8_UINT;
                texture = dev.metallium.backend.MetalliumInterop.wrapTexture(metal, format, label);
                view = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture);
                handle = metal.handle();
            }
            return view;
        }

        void close() {
            if (view != null) {
                view.close();
                view = null;
            }
            if (texture != null) {
                texture.close();
                texture = null;
            }
            handle = 0;
        }
    }

    // ---------------------------------------------------------------------------------------------- reflection

    private record Reflection(Class<?> programSet, Method getPatchData, Method getSamplerSet, Method getOpaqueTargets, Method getTranslucentTargets,
                              Method buildPipeline, Constructor<?> pipelineConstructor, Field thePipeline, Field fb, Field fbTranslucent,
                              Method getDepthTex, Field glTextureId, Field capturedViewport, Field usedIrisViewport, Method renderSystem,
                              Method applyViewport) {
    }

    private static synchronized @Nullable Reflection reflection() {
        if (reflection != null || reflectionFailed) {
            return reflection;
        }
        try {
            ClassLoader loader = VoxyIrisBridge.class.getClassLoader();
            Class<?> patchHolder = Class.forName(PATCH_HOLDER, false, loader);
            Class<?> patch = Class.forName(PATCH, false, loader);
            Class<?> data = Class.forName(DATA, false, loader);
            Class<?> pipeline = Class.forName(PIPELINE, false, loader);
            Class<?> abstractPipeline = Class.forName(ABSTRACT_PIPELINE, false, loader);
            Class<?> depthFb = Class.forName(DEPTH_FB, false, loader);
            Class<?> glTexture = Class.forName(GL_TEXTURE, false, loader);
            Class<?> irisUtil = Class.forName(IRIS_UTIL, false, loader);
            Class<?> captured = Class.forName(CAPTURED, false, loader);
            Class<?> holder = Class.forName(HOLDER, false, loader);
            Class<?> renderSystem = Class.forName(RENDER_SYSTEM, false, loader);
            Constructor<?> constructor = null;
            for (Constructor<?> c : pipeline.getConstructors()) {
                if (c.getParameterCount() == 6 && c.getParameterTypes()[1] == data) {
                    constructor = c;
                }
            }
            if (constructor == null) {
                throw new NoSuchMethodException(PIPELINE + ".<init>(RenderProperties, " + DATA + ", ...)");
            }
            reflection = new Reflection(patchHolder,
                    patchHolder.getMethod("voxy$getPatchData"),
                    patch.getMethod("getSamplerSet"),
                    patch.getMethod("getOpqaueTargets"),
                    patch.getMethod("getTranslucentTargets"),
                    data.getMethod("buildPipeline", IrisRenderingPipeline.class, patch, net.irisshaders.iris.uniforms.custom.CustomUniforms.class,
                            net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder.class),
                    constructor,
                    data.getField("thePipeline"),
                    abstractPipeline.getField("fb"),
                    pipeline.getField("fbTranslucent"),
                    depthFb.getMethod("getDepthTex"),
                    glTexture.getField("id"),
                    irisUtil.getField("CAPTURED_VIEWPORT_PARAMETERS"),
                    irisUtil.getField("USED_IRIS_VIEWPORT"),
                    holder.getMethod("getNullable"),
                    captured.getMethod("apply", renderSystem));
        } catch (ReflectiveOperationException | LinkageError e) {
            reflectionFailed = true;
            lastError = "this Voxy version is not supported: " + e;
            MetalliumIris.LOGGER.error("[metallium-iris] Voxy: its shader-pack integration was not found (unsupported Voxy version); LODs stay paused with shaders", e);
        }
        return reflection;
    }

    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
