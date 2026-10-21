/*
 * Copyright (c) 2026 Crash09N – NPR Media, LGPL-3.0 (this module's licence).
 */
package de.nprmedia.nativium.iris.post;

import com.mojang.renderpearl.api.commands.RenderPass;
import de.nprmedia.nativium.iris.NativiumIris;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Physics Mod (closed source, compatibility only: nothing of it is used) draws its debris, ragdolls and cloth into the
 * shadow map through mixins into Iris's {@code ShadowRenderer.renderShadows}. On Metal {@link MetalShadowRenderer}
 * renders the shadow pass instead, so those hooks never ran: Physics Mod objects cast no shadow, and while the shadow
 * pass ran, Physics Mod still held the player camera matrices. This calls the same Physics Mod methods at the same points
 * (looked up reflectively; a no-op without Physics Mod): before the entity frame is prepared (shadow matrices, prepare
 * its geometry, submit dynamic cloth), after the entity features are drawn (its objects in the entity phase, in the same
 * render pass), and at the end (back to the player camera).
 */
final class PhysicsModShadows {
    private static final @Nullable PhysicsModShadows INSTANCE = create();

    private final Class<?> levelAccessor;
    private final MethodHandle getLevel;
    private final MethodHandle getMainRenderer;
    private final MethodHandle setCameraPosition;
    private final MethodHandle setViewAndProjection;
    private final MethodHandle getStoredView;
    private final MethodHandle getStoredProjection;
    private final MethodHandle prepareMainRendering;
    private final MethodHandle prepareStaticCloth;
    private final MethodHandle renderDynamicCloth;
    private final MethodHandle renderAll;
    private final MethodHandle renderStaticCloth;
    private final @Nullable MethodHandle setClothSkipRenderQueue;
    private boolean failed;

    private PhysicsModShadows(final ClassLoader loader) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        levelAccessor = Class.forName("net.diebuddies.minecraft.LevelRendererAccessor", false, loader);
        Class<?> mainRenderer = Class.forName("net.diebuddies.render.MainRenderer", false, loader);
        Class<?> level = net.minecraft.client.multiplayer.ClientLevel.class;
        getLevel = lookup.findVirtual(levelAccessor, "physicsmod$getLevel", MethodType.methodType(level));
        getMainRenderer = lookup.findVirtual(levelAccessor, "physicsmod$getMainRenderer", MethodType.methodType(mainRenderer));
        setCameraPosition = lookup.findVirtual(mainRenderer, "setCameraPosition", MethodType.methodType(void.class, Vec3.class));
        setViewAndProjection = lookup.findVirtual(mainRenderer, "setViewAndProjectionMatrix", MethodType.methodType(void.class, Matrix4fc.class, Matrix4fc.class));
        getStoredView = lookup.findVirtual(mainRenderer, "getStoredViewMatrix", MethodType.methodType(Matrix4f.class));
        getStoredProjection = lookup.findVirtual(mainRenderer, "getStoredProjectionMatrix", MethodType.methodType(Matrix4f.class));
        prepareMainRendering = lookup.findVirtual(mainRenderer, "prepareMainRendering", MethodType.methodType(void.class, level, ChunkSectionLayer.class));
        prepareStaticCloth = lookup.findVirtual(mainRenderer, "prepareStaticCloth", MethodType.methodType(void.class, level));
        renderDynamicCloth = lookup.findVirtual(mainRenderer, "renderDynamicCloth", MethodType.methodType(void.class, level, Matrix4f.class, SubmitNodeStorage.class));
        renderAll = lookup.findVirtual(mainRenderer, "renderAll", MethodType.methodType(void.class, level, ChunkSectionLayer.class, RenderPass.class));
        renderStaticCloth = lookup.findVirtual(mainRenderer, "renderStaticCloth", MethodType.methodType(void.class, level, RenderPass.class));
        MethodHandle skip = null;
        try {
            skip = lookup.findStaticSetter(Class.forName("net.diebuddies.physics.PhysicsMod", false, loader), "clothSkipRenderQueue", boolean.class);
        } catch (ReflectiveOperationException ignored) {
            // optional (cloth entities only)
        }
        setClothSkipRenderQueue = skip;
    }

    private static @Nullable PhysicsModShadows create() {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("physicsmod")) {
            return null;
        }
        try {
            return new PhysicsModShadows(PhysicsModShadows.class.getClassLoader());
        } catch (ReflectiveOperationException | LinkageError e) {
            NativiumIris.LOGGER.warn("[nativium-iris] Physics Mod found, but its shadow hooks are not available ({}): its objects cast no shadows", e.toString());
            return null;
        }
    }

    /** The Physics Mod main renderer and level for this level renderer, or {@code null} without Physics Mod. */
    static @Nullable Context context(final Object levelRenderer) {
        PhysicsModShadows self = INSTANCE;
        if (self == null || self.failed || !self.levelAccessor.isInstance(levelRenderer)) {
            return null;
        }
        try {
            Object level = self.getLevel.invoke(levelRenderer);
            return level == null ? null : new Context(self, level, self.getMainRenderer.invoke(levelRenderer));
        } catch (Throwable t) {
            self.fail(t);
            return null;
        }
    }

    private void fail(final Throwable t) {
        failed = true;
        NativiumIris.LOGGER.warn("[nativium-iris] Physics Mod shadow hook failed, its objects no longer cast shadows", t);
    }

    /** Around the shadow pass's entity extraction (Physics Mod's cloth entities then submit normally). */
    static void clothSkipRenderQueue(final boolean skip) {
        PhysicsModShadows self = INSTANCE;
        if (self != null && self.setClothSkipRenderQueue != null && !self.failed) {
            try {
                self.setClothSkipRenderQueue.invoke(skip);
            } catch (Throwable t) {
                self.fail(t);
            }
        }
    }

    record Context(PhysicsModShadows mod, Object level, Object renderer) {
        /** Before the entity frame is prepared: Iris's shadow matrices, its geometry, dynamic cloth submissions. */
        void prepare(final Vec3 cameraPosition, final Matrix4f shadowModelView, final Matrix4f shadowProjection, final SubmitNodeStorage storage) {
            try {
                mod.setCameraPosition.invoke(renderer, cameraPosition);
                mod.setViewAndProjection.invoke(renderer, (Matrix4fc) shadowModelView, (Matrix4fc) shadowProjection);
                mod.prepareMainRendering.invoke(renderer, level, ChunkSectionLayer.CUTOUT);
                mod.prepareStaticCloth.invoke(renderer, level);
                mod.renderDynamicCloth.invoke(renderer, level, shadowModelView, storage);
            } catch (Throwable t) {
                mod.fail(t);
            }
        }

        /** After the entity features, in the same render pass (entity phase: the pack's shadow entity program). */
        void draw(final RenderPass pass) {
            if (mod.failed) {
                return;
            }
            try {
                mod.renderAll.invoke(renderer, level, ChunkSectionLayer.CUTOUT, pass);
                mod.renderStaticCloth.invoke(renderer, level, pass);
            } catch (Throwable t) {
                mod.fail(t);
            }
        }

        /** End of the shadow pass: back to the player camera. */
        void restore(final Vec3 cameraPosition) {
            try {
                mod.setCameraPosition.invoke(renderer, cameraPosition);
                mod.setViewAndProjection.invoke(renderer, (Matrix4fc) mod.getStoredView.invoke(renderer), (Matrix4fc) mod.getStoredProjection.invoke(renderer));
            } catch (Throwable t) {
                mod.fail(t);
            }
        }
    }
}
