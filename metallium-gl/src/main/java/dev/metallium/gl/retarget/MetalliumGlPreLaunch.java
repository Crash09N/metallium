/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.retarget;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Fallback if the mixin plugin did not run first (normally a no-op: {@link MetalliumGlBootstrap#install} is idempotent). */
public final class MetalliumGlPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        MetalliumGlBootstrap.install();
    }
}
