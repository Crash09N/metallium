/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.retarget;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Fallback if the mixin plugin did not run first (normally a no-op: {@link NativiumGlBootstrap#install} is idempotent). */
public final class NativiumGlPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        NativiumGlBootstrap.install();
    }
}
