/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import java.util.List;

/** What metallium-gl reports through glGetString(i) and GLCapabilities. */
final class MetalGLStrings {
    /**
     * Extensions that are actually implemented (core-profile 4.6 is claimed as the version, but mods probe features through
     * extensions, so only working ones are listed).
     */
    static final List<String> EXTENSIONS = List.of(
            "GL_ARB_vertex_array_object",
            "GL_ARB_framebuffer_object",
            "GL_ARB_map_buffer_range",
            "GL_ARB_buffer_storage",
            "GL_ARB_texture_storage",
            "GL_ARB_instanced_arrays",
            "GL_ARB_draw_instanced",
            "GL_ARB_draw_elements_base_vertex",
            "GL_ARB_sync",
            "GL_ARB_explicit_attrib_location",
            "GL_EXT_texture_filter_anisotropic"
    );

    private MetalGLStrings() {
    }
}
