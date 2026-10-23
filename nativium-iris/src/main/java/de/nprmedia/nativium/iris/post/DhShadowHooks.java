/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import de.nprmedia.nativium.iris.NativiumIris;
import org.jspecify.annotations.Nullable;

/**
 * The Distant Horizons API side of {@link DhIrisBridge}'s LOD shadows (docs/design/iris-voxy-dh.md, section 4, item 5).
 * Kept apart so {@link DhIrisBridge} loads without DH installed: this class is only touched once DH is known to be
 * present.
 */
final class DhShadowHooks {
    /** 0 = not tried, 1 = Iris's cancel handlers replaced, -1 = failed (dh_shadow stays off). */
    private static int hookState;
    private static com.seibel.distanthorizons.api.interfaces.override.@Nullable IDhApiOverrideable boundFrustum;

    private DhShadowHooks() {
    }

    /** DH's render entry point (what DH's own hook calls), unless DH cannot render yet (like its canRenderOrThrow). */
    static void renderLods() {
        String unable = com.seibel.distanthorizons.core.api.internal.ClientApi.RENDER_STATE.unableToRenderBecause();
        if (unable != null && !unable.isEmpty()) {
            return;
        }
        com.seibel.distanthorizons.core.api.internal.ClientApi.INSTANCE.renderLods();
    }

    /**
     * Iris binds two DH event handlers ({@code LodRendererEvents.setupTransparentRendererEventCancling}) that cancel
     * DH's rendering during Iris's shadow pass unless Iris's OpenGL DH pipeline has a shadow program. On Metal that
     * pipeline never exists, so they are replaced (same rule, plus "unless dh_shadow runs on Metal"). Iris's handlers
     * are anonymous classes; they are found by their enclosing method. A handler for DH's render setup binds Iris's
     * shadow frustum as DH's shadow culling frustum (as Iris does for its own pipeline), else DH would draw every LOD
     * into the shadow map. Only called on Metal (the bridge belongs to {@link MetalPostPipeline}).
     */
    static synchronized boolean hookShadowEvents() {
        if (hookState != 0) {
            return hookState > 0;
        }
        hookState = -1;
        try {
            int replaced = 0;
            for (int i = 1; i <= 64; i++) {
                Class<?> type;
                try {
                    type = Class.forName("net.irisshaders.iris.compat.dh.LodRendererEvents$" + i);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                java.lang.reflect.Method enclosing = type.getEnclosingMethod();
                if (enclosing == null || !enclosing.getName().equals("setupTransparentRendererEventCancling")
                        || !com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent.class.isAssignableFrom(type)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Class<? extends com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent<?>> key =
                        (Class<? extends com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent<?>>) type.getSuperclass();
                @SuppressWarnings("unchecked")
                Class<? extends com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent<?>> handler =
                        (Class<? extends com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent<?>>) type;
                if (com.seibel.distanthorizons.api.DhApi.events.unbind(key, handler)) {
                    replaced++;
                }
            }
            if (replaced != 2) {
                NativiumIris.LOGGER.warn("[nativium-iris] Distant Horizons: Iris's shadow-pass DH handlers not found ({} of 2); no LOD shadows", replaced);
                return false;
            }
            com.seibel.distanthorizons.api.DhApi.events.bind(com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent.class,
                    new com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent() {
                        @Override
                        public void beforeRender(final com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<
                                com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam> event) {
                            if (DhIrisBridge.cancelShadowRender()) {
                                event.cancelEvent();
                            }
                        }
                    });
            com.seibel.distanthorizons.api.DhApi.events.bind(com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent.class,
                    new com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent() {
                        @Override
                        public void beforeRender(final com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<
                                com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam> event) {
                            if (DhIrisBridge.cancelShadowRender()) {
                                event.cancelEvent();
                            }
                        }
                    });
            // Bound after Iris's own setup handler (which unbinds ShadowRenderer.FRUSTUM every render): runs after it.
            com.seibel.distanthorizons.api.DhApi.events.bind(com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderSetupEvent.class,
                    new com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderSetupEvent() {
                        @Override
                        public void beforeSetup(final com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam<
                                com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam> event) {
                            unbindFrustum();
                            if (DhIrisBridge.shadowRunning()) {
                                bindFrustum();
                            }
                        }
                    });
            hookState = 1;
            NativiumIris.LOGGER.info("[nativium-iris] Distant Horizons: Iris's shadow-pass DH handlers replaced (dh_shadow on Metal)");
            return true;
        } catch (RuntimeException | LinkageError e) {
            NativiumIris.LOGGER.warn("[nativium-iris] Distant Horizons: could not hook DH's shadow-pass events; no LOD shadows", e);
            return false;
        }
    }

    private static synchronized void bindFrustum() {
        if (net.irisshaders.iris.shadows.ShadowRenderer.FRUSTUM instanceof com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum
                && net.irisshaders.iris.shadows.ShadowRenderer.FRUSTUM instanceof com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable frustum) {
            try {
                com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector.INSTANCE.bind(
                        com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum.class, frustum);
                boundFrustum = frustum;
            } catch (RuntimeException e) {
                NativiumIris.LOGGER.debug("[nativium-iris] DH shadow culling frustum not bound", e);
            }
        }
    }

    static synchronized void unbindFrustum() {
        if (boundFrustum != null) {
            try {
                com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector.INSTANCE.unbind(
                        com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum.class, boundFrustum);
            } catch (RuntimeException e) {
                NativiumIris.LOGGER.debug("[nativium-iris] DH shadow culling frustum not unbound", e);
            }
            boundFrustum = null;
        }
    }
}
