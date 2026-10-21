// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package dev.metallium.api;

import de.nprmedia.nativium.api.NativiumRuntime;

/**
 * Compatibility shim for mods built against Metallium, the project's name before it was renamed to Nativium.
 * Every method delegates to {@link NativiumRuntime}; use that class instead. Kept for one release, then removed.
 *
 * @deprecated since 0.1.0-alpha: use {@link de.nprmedia.nativium.api.NativiumRuntime}
 */
@Deprecated(since = "0.1.0-alpha", forRemoval = true)
public final class MetalliumRuntime {
    private MetalliumRuntime() {
    }

    /** @deprecated use {@link NativiumRuntime#isMetalActive()} */
    @Deprecated(since = "0.1.0-alpha", forRemoval = true)
    public static boolean isMetalActive() {
        return NativiumRuntime.isMetalActive();
    }

    /** @deprecated use {@link NativiumRuntime#registerStatusLine(java.util.function.Supplier)} */
    @Deprecated(since = "0.1.0-alpha", forRemoval = true)
    public static void registerStatusLine(final java.util.function.Supplier<String> line) {
        NativiumRuntime.registerStatusLine(line);
    }

    /** @deprecated use {@link NativiumRuntime#statusLines()} */
    @Deprecated(since = "0.1.0-alpha", forRemoval = true)
    public static java.util.List<String> statusLines() {
        return NativiumRuntime.statusLines();
    }

    /** @deprecated use {@link NativiumRuntime#metalDescription()} */
    @Deprecated(since = "0.1.0-alpha", forRemoval = true)
    public static String metalDescription() {
        return NativiumRuntime.metalDescription();
    }
}
