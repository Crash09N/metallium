/*
 * Adapted from Iris's net.irisshaders.iris.shadows.ShadowRenderer (https://github.com/IrisShaders/Iris, branch 26.3),
 * Copyright (c) IrisShaders contributors, licensed under the GNU Lesser General Public License v3.0.
 * Modifications for Metallium's Metal backend (no OpenGL state, render passes retargeted by GbufferPasses):
 * Copyright (c) 2026 Metallium contributors, LGPL-3.0 (this module's licence).
 */
package dev.metallium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ShadowCullState;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.CullEverythingFrustum;
import net.irisshaders.iris.shadows.frustum.FrustumHolder;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.SafeZoneCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Iris adapter stage 4: the shadow pass on Metal. The same steps as Iris's {@code ShadowRenderer} (sun-view model-view
 * and orthographic projection, shadow frustum culling, Sodium's separate shadow render lists via Iris's unchanged Sodium
 * mixins, opaque terrain, entities and block entities, the {@code shadowtex1} copy, translucent terrain), but without
 * OpenGL: the render passes it opens on the main target are retargeted by {@link GbufferPasses} to the shadow targets,
 * and their pipelines replaced by the pack's {@code shadow*} programs (cull off, forward-Z depth test) - see
 * {@link GbufferState}. Viewport = shadow map size comes from the attachment size of the retargeted pass.
 */
final class MetalShadowRenderer {
    private final MetalPostPipeline pipeline;
    private final ShadowTargets targets;
    private final float halfPlaneLength;
    private final float nearPlane;
    private final float farPlane;
    private final float voxelDistance;
    private final float renderDistanceMultiplier;
    private final float entityShadowDistanceMultiplier;
    private final int resolution;
    private final float intervalSize;
    private final Float fov;
    private final ShadowCullState packCullingState;
    private final boolean packHasVoxelization;
    private final boolean shouldRenderTerrain;
    private final boolean shouldRenderTranslucent;
    private final boolean shouldRenderEntities;
    private final boolean shouldRenderPlayer;
    private final boolean shouldRenderBlockEntities;
    private final boolean shouldRenderLightBlockEntities;
    private final boolean shouldRenderDH;
    private final float sunPathRotation;
    private final RenderBuffers buffers;
    private final LevelRenderState levelRenderState = new LevelRenderState();
    private final SubmitNodeStorage submitNodeStorage = new SubmitNodeStorage();
    private final FeatureRenderDispatcher featureRenderDispatcher;
    private final net.minecraft.client.renderer.ProjectionMatrixBuffer projectionBuffer = new net.minecraft.client.renderer.ProjectionMatrixBuffer("metallium iris shadow projection");
    private FrustumHolder terrainFrustumHolder = new FrustumHolder();
    private FrustumHolder entityFrustumHolder = new FrustumHolder();
    int renderedEntities;
    int renderedBlockEntities;

    MetalShadowRenderer(final MetalPostPipeline pipeline, final ProgramSource shadow, final PackDirectives directives, final ShadowTargets targets) {
        this.pipeline = pipeline;
        this.targets = targets;
        PackShadowDirectives shadowDirectives = directives.getShadowDirectives();
        this.halfPlaneLength = shadowDirectives.getDistance();
        this.nearPlane = shadowDirectives.getNearPlane();
        this.farPlane = shadowDirectives.getFarPlane();
        this.voxelDistance = shadowDirectives.getVoxelDistance();
        this.renderDistanceMultiplier = shadowDirectives.getDistanceRenderMul();
        this.entityShadowDistanceMultiplier = shadowDirectives.getEntityShadowDistanceMul();
        this.resolution = targets.resolution;
        this.intervalSize = shadowDirectives.getIntervalSize();
        this.shouldRenderTerrain = shadowDirectives.shouldRenderTerrain();
        this.shouldRenderTranslucent = shadowDirectives.shouldRenderTranslucent();
        this.shouldRenderEntities = shadowDirectives.shouldRenderEntities();
        this.shouldRenderPlayer = shadowDirectives.shouldRenderPlayer();
        this.shouldRenderBlockEntities = shadowDirectives.shouldRenderBlockEntities();
        this.shouldRenderLightBlockEntities = shadowDirectives.shouldRenderLightBlockEntities();
        this.shouldRenderDH = shadowDirectives.isDhShadowEnabled().orElse(false);
        this.fov = shadowDirectives.getFov();
        // Iris assumes voxelization (no frustum culling) with a geometry shader; images need stage 5 on Metal.
        this.packHasVoxelization = shadow != null && shadow.getGeometrySource().isPresent();
        this.packCullingState = shadow != null ? shadowDirectives.getCullingState() : ShadowCullState.DEFAULT;
        this.sunPathRotation = directives.getSunPathRotation();
        this.buffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
        Minecraft minecraft = Minecraft.getInstance();
        this.featureRenderDispatcher = new FeatureRenderDispatcher(buffers, minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font,
                minecraft.gameRenderer.gameRenderState());
    }

    private FrustumHolder createShadowFrustum(float renderMultiplier, final FrustumHolder holder) {
        String distanceInfo;
        String cullingInfo;
        int renderDistanceBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        if ((packCullingState == ShadowCullState.DEFAULT && packHasVoxelization) || packCullingState == ShadowCullState.DISTANCE) {
            double distance = halfPlaneLength * renderMultiplier;
            String reason = packCullingState == ShadowCullState.DISTANCE ? "(set by shader pack)" : "(voxelization detected)";
            if (distance <= 0 || distance > renderDistanceBlocks) {
                return holder.setInfo(new NonCullingFrustum(), "render distance = " + renderDistanceBlocks + " blocks", "disabled " + reason);
            }
            return holder.setInfo(new BoxCullingFrustum(new BoxCuller(distance)), distance + " blocks (set by shader pack)", "distance only " + reason);
        }
        boolean hasSafeZone = packCullingState == ShadowCullState.SAFE_ZONE;
        if (hasSafeZone && renderMultiplier < 0) {
            renderMultiplier = 1.0f;
        }
        double distance = (hasSafeZone ? voxelDistance : halfPlaneLength) * renderMultiplier;
        String setter = "(set by shader pack)";
        if (renderMultiplier < 0) {
            distance = IrisVideoSettings.shadowDistance * 16;
            setter = "(set by user)";
        }
        BoxCuller boxCuller;
        if (distance >= renderDistanceBlocks && !hasSafeZone) {
            distanceInfo = "render distance = " + renderDistanceBlocks + " blocks";
            boxCuller = null;
        } else {
            distanceInfo = distance + " blocks " + setter;
            if (distance == 0.0 && !hasSafeZone) {
                return holder.setInfo(new CullEverythingFrustum(), distanceInfo, "no shadows rendered");
            }
            boxCuller = new BoxCuller(distance);
        }
        cullingInfo = (hasSafeZone ? "Safe Zone" : "Advanced") + " Frustum Culling enabled";
        Vector4f shadowLightPosition = new CelestialUniforms(sunPathRotation).getShadowLightPositionInWorldSpace();
        Vector3f shadowLightVectorFromOrigin = new Vector3f(shadowLightPosition.x(), shadowLightPosition.y(), shadowLightPosition.z()).normalize();
        Matrix4f projView = ((shouldRenderDH && DHCompat.hasRenderingEnabled()) ? DHCompat.getProjection() : CapturedRenderingState.INSTANCE.getGbufferProjection())
                .mul(CapturedRenderingState.INSTANCE.getGbufferModelView(), new Matrix4f());
        if (hasSafeZone) {
            return holder.setInfo(new SafeZoneCullingFrustum(projView, ShadowRenderer.PROJECTION, shadowLightVectorFromOrigin, boxCuller,
                    new BoxCuller(halfPlaneLength * renderMultiplier)), distanceInfo, cullingInfo);
        }
        return holder.setInfo(new AdvancedShadowCullingFrustum(projView, ShadowRenderer.PROJECTION, shadowLightVectorFromOrigin, boxCuller), distanceInfo, cullingInfo);
    }

    /** Iris's {@code ShadowRenderer.renderShadows}, minus the GL state (see the class comment). */
    void renderShadows(final LevelRendererAccessor levelRenderer, final Camera playerCamera) {
        if (IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance) == 0) {
            return;
        }
        targets.beginFrame();
        GpuSampler theSampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, true);
        playerCamera.extractRenderState(levelRenderState.cameraRenderState, Minecraft.getInstance().getDeltaTracker());
        Minecraft client = Minecraft.getInstance();

        Profiler.get().popPush("shadows");
        ShadowRenderer.ACTIVE = true;
        ShadowRenderer.RESOLUTION = resolution;
        ShadowRenderer.renderDistance = renderDistanceMultiplier < 0 ? IrisVideoSettings.shadowDistance : (int) ((halfPlaneLength * renderDistanceMultiplier) / 16);
        ShadowRenderer.visibleBlockEntities = new ArrayList<>();

        RenderBuffers playerBuffers = levelRenderer.getRenderBuffers();
        levelRenderer.setRenderBuffers(buffers);

        PoseStack modelView = ShadowRenderer.createShadowModelView(sunPathRotation, intervalSize, nearPlane, farPlane);
        ShadowRenderer.MODELVIEW = new Matrix4f(modelView.last().pose());
        Matrix4f cameraViewRotation = new Matrix4f(levelRenderState.cameraRenderState.viewRotationMatrix);
        Matrix4f cameraProjection = new Matrix4f(levelRenderState.cameraRenderState.projectionMatrix);
        levelRenderState.cameraRenderState.viewRotationMatrix = ShadowRenderer.MODELVIEW;
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().set(ShadowRenderer.MODELVIEW);

        Matrix4f shadowProjection = fov != null ? ShadowMatrices.createPerspectiveMatrix(fov)
                : ShadowMatrices.createOrthoMatrix(halfPlaneLength,
                Mth.equal(nearPlane, -1.0f) ? -DHCompat.getRenderDistance() * 16 : nearPlane,
                Mth.equal(farPlane, -1.0f) ? DHCompat.getRenderDistance() * 16 : farPlane);
        levelRenderState.cameraRenderState.projectionMatrix = shadowProjection;
        // IrisRenderSystem.setShadowProjection, with our own buffer (RenderSystem's projection is restored below).
        var backupProjection = RenderSystem.getProjectionMatrixBuffer();
        var backupProjectionType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(shadowProjection), com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
        ShadowRenderer.PROJECTION = shadowProjection;

        if (levelRenderer instanceof CullingDataCache cache) {
            cache.saveState();
        }
        terrainFrustumHolder = createShadowFrustum(renderDistanceMultiplier, terrainFrustumHolder);
        ShadowRenderer.FRUSTUM = terrainFrustumHolder.getFrustum();
        Vector3d cameraPos = CameraUniforms.getUnshiftedCameraPosition();
        double cameraX = cameraPos.x();
        double cameraY = cameraPos.y();
        double cameraZ = cameraPos.z();
        terrainFrustumHolder.getFrustum().prepare(cameraX, cameraY, cameraZ);

        boolean wasChunkCullingEnabled = client.smartCull;
        client.smartCull = false;
        ChunkRenderMatrices playerMatrices = ((LevelRendererExtension) levelRenderer).sodium$getMatrices();
        ((LevelRendererExtension) levelRenderer).sodium$setMatrices(new ChunkRenderMatrices(shadowProjection, ShadowRenderer.MODELVIEW));
        SodiumWorldRenderer sodiumWorldRenderer = ((LevelRendererExtension) levelRenderer).sodium$getWorldRenderer();
        if (sodiumWorldRenderer instanceof ShadowRenderListAccess access) {
            access.iris$beginShadowRenderListScope();
        }
        try {
            sodiumWorldRenderer.scheduleTerrainUpdate();
            Frustum shadowFrustum = terrainFrustumHolder.getFrustum();
            sodiumWorldRenderer.setupTerrain(playerCamera, ((ViewportProvider) shadowFrustum).sodium$createViewport(),
                    ((FogStorage) client.gameRenderer).sodium$getFogParameters(),
                    playerCamera.entity() != null && playerCamera.entity().isSpectator(), false, ((FrustumAccessor) shadowFrustum).sodium$getMatrix());
            client.smartCull = wasChunkCullingEnabled;

            Profiler.get().popPush("terrain");
            ChunkSectionsToRender sections = new SodiumChunkSection(sodiumWorldRenderer, ((LevelRendererExtension) levelRenderer).sodium$getMatrices(), cameraX, cameraY, cameraZ);
            SodiumWorldRenderer.instance().prepareChunkRendering(((LevelRendererExtension) levelRenderer).sodium$getMatrices(),
                    levelRenderState.cameraRenderState.pos.x, levelRenderState.cameraRenderState.pos.y, levelRenderState.cameraRenderState.pos.z);
            var atlas = client.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getTextureView();
            if (shouldRenderTerrain) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_SOLID);
                try (RenderPass renderPass = mainTargetPass("shadow terrain")) {
                    sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, renderPass, theSampler, atlas, false);
                }
                pipeline.setPhase(WorldRenderingPhase.NONE);
            }

            pipeline.setPhase(WorldRenderingPhase.ENTITIES);
            Profiler.get().popPush("entities");
            float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
            if (entityShadowDistanceMultiplier == 1.0F || entityShadowDistanceMultiplier < 0.0F) {
                entityFrustumHolder.setInfo(terrainFrustumHolder.getFrustum(), terrainFrustumHolder.getDistanceInfo(), terrainFrustumHolder.getCullingInfo());
            } else {
                entityFrustumHolder = createShadowFrustum(renderDistanceMultiplier * entityShadowDistanceMultiplier, entityFrustumHolder);
            }
            Frustum entityShadowFrustum = entityFrustumHolder.getFrustum();
            entityShadowFrustum.prepare(cameraX, cameraY, cameraZ);
            levelRenderState.reset();
            if (shouldRenderEntities) {
                extractVisibleEntities(playerCamera, entityShadowFrustum, client.getDeltaTracker(), levelRenderState);
            } else if (shouldRenderPlayer) {
                Player player = client.player;
                float g = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                if (!player.isSpectator() && !player.isInvisible()) {
                    levelRenderState.entityRenderStates.add(client.getEntityRenderDispatcher().extractEntity(player, g));
                }
                if (player.getVehicle() != null) {
                    levelRenderState.entityRenderStates.add(client.getEntityRenderDispatcher().extractEntity(player.getVehicle(), g));
                }
            }
            RenderSystem.getModelViewStack().identity();
            for (EntityRenderState state : levelRenderState.entityRenderStates) {
                client.getEntityRenderDispatcher().submit(state, levelRenderState.cameraRenderState, state.x - cameraX, state.y - cameraY, state.z - cameraZ, modelView, submitNodeStorage);
            }
            renderedEntities = levelRenderState.entityRenderStates.size();

            Profiler.get().popPush("build blockentities");
            if (shouldRenderBlockEntities || shouldRenderLightBlockEntities) {
                sodiumWorldRenderer.extractBlockEntities(playerCamera, tickDelta, client.level.destructionProgress(), levelRenderState);
                if (!shouldRenderBlockEntities) {
                    Iterator<BlockEntityRenderState> it = levelRenderState.blockEntityRenderStates.iterator();
                    while (it.hasNext()) {
                        if (((dev.metallium.iris.mixin.BlockEntityRenderStateAccessor) it.next()).metallium$getBlockState().getLightEmission() == 0) {
                            it.remove();
                        }
                    }
                }
            }
            renderedBlockEntities = renderBlockEntities(modelView, playerCamera);

            Profiler.get().popPush("draw entities");
            var frame = featureRenderDispatcher.prepareFrame(submitNodeStorage);
            try (RenderPass renderPass = mainTargetPass("shadow entities")) {
                FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
            }
            frame.close();
            buffers.endFrame();

            Profiler.get().popPush("translucent depth copy");
            targets.copyPreTranslucentDepth();
            RenderSystem.getModelViewStack().set(ShadowRenderer.MODELVIEW);

            Profiler.get().popPush("translucent terrain");
            pipeline.setPhase(WorldRenderingPhase.NONE);
            if (shouldRenderTranslucent) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_TRANSLUCENT);
                try (RenderPass renderPass = mainTargetPass("shadow translucent terrain")) {
                    sections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, renderPass, theSampler, atlas, false);
                }
                pipeline.setPhase(WorldRenderingPhase.NONE);
            }
        } finally {
            if (sodiumWorldRenderer instanceof ShadowRenderListAccess access) {
                access.iris$endShadowRenderListScope();
            }
            RenderSystem.setProjectionMatrix(backupProjection, backupProjectionType);
            ((LevelRendererExtension) levelRenderer).sodium$setMatrices(playerMatrices);
            if (levelRenderer instanceof CullingDataCache cache) {
                cache.restoreState();
            }
            levelRenderer.setRenderBuffers(playerBuffers);
            ShadowRenderer.visibleBlockEntities = null;
            ShadowRenderer.ACTIVE = false;
            levelRenderState.cameraRenderState.viewRotationMatrix = cameraViewRotation;
            levelRenderState.cameraRenderState.projectionMatrix = cameraProjection;
            RenderSystem.getModelViewStack().popMatrix();
            pipeline.setPhase(WorldRenderingPhase.NONE);
        }
        Profiler.get().popPush("updatechunks");
    }

    /** Opened on the main target like Iris does; {@link GbufferPasses} retargets it to the shadow attachments. */
    private static RenderPass mainTargetPass(final String label) {
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, main.getColorTextureView(), Optional.empty(),
                main.getDepthTextureView(), OptionalDouble.empty());
    }

    private int renderBlockEntities(final PoseStack poseStack, final Camera camera) {
        Vec3 position = camera.position();
        int count = 0;
        for (BlockEntityRenderState state : levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = state.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockPos.getX() - position.x(), blockPos.getY() - position.y(), blockPos.getZ() - position.z());
            Minecraft.getInstance().getBlockEntityRenderDispatcher().submit(state, poseStack, submitNodeStorage, levelRenderState.cameraRenderState);
            poseStack.popPose();
            count++;
        }
        return count;
    }

    private static void extractVisibleEntities(final Camera camera, final Frustum frustum, final DeltaTracker deltaTracker, final LevelRenderState levelRenderState) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 position = camera.position();
        TickRateManager tickRateManager = minecraft.level.tickRateManager();
        Entity.setViewScale(Mth.clamp(minecraft.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * minecraft.options.entityDistanceScaling().get());
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof AbstractClientPlayer player && player.isSpectator()) {
                continue;
            }
            if (minecraft.getEntityRenderDispatcher().shouldRender(entity, frustum, position.x(), position.y(), position.z(), levelRenderState.worldPartialTicks)
                    || entity.hasIndirectPassenger(minecraft.player)) {
                BlockPos blockPos = entity.blockPosition();
                if (minecraft.level.isOutsideBuildHeight(blockPos.getY()) || minecraft.levelRenderer.isSectionCompiledAndVisible(blockPos, 0)) {
                    if (entity.tickCount == 0) {
                        entity.xOld = entity.getX();
                        entity.yOld = entity.getY();
                        entity.zOld = entity.getZ();
                    }
                    float partialTick = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
                    levelRenderState.entityRenderStates.add(minecraft.getEntityRenderDispatcher().extractEntity(entity, partialTick));
                }
            }
        }
    }

    void close() {
        projectionBuffer.close();
    }

    String debugInfo() {
        return "shadow map " + resolution + "x" + resolution + ", " + terrainFrustumHolder.getDistanceInfo() + ", " + terrainFrustumHolder.getCullingInfo()
                + ", entities " + renderedEntities + ", block entities " + renderedBlockEntities;
    }
}
