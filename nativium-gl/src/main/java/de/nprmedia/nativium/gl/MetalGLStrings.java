/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import java.util.List;

/** What nativium-gl reports through glGetString(i) and GLCapabilities. */
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
            "GL_EXT_texture_filter_anisotropic",
            // GL 4.x (compute / GPU-driven rendering)
            "GL_ARB_compute_shader",
            "GL_ARB_shader_storage_buffer_object",
            "GL_ARB_shader_image_load_store",
            "GL_ARB_uniform_buffer_object",
            "GL_ARB_copy_buffer",
            "GL_ARB_clear_buffer_object",
            "GL_ARB_draw_indirect",
            "GL_ARB_multi_draw_indirect",
            "GL_ARB_indirect_parameters",
            "GL_ARB_base_instance",
            "GL_ARB_shader_draw_parameters",
            "GL_ARB_direct_state_access",
            "GL_ARB_sampler_objects",
            "GL_ARB_texture_view",
            "GL_ARB_texture_barrier",
            "GL_ARB_clip_control",
            "GL_ARB_timer_query",
            "GL_KHR_debug",
            "GL_KHR_shader_subgroup"
    );

    private MetalGLStrings() {
    }
}
