package dev.metallium.iris.post;

import com.mojang.renderpearl.backend.api.SpvModule;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5: storage images and SSBOs in render programs (gbuffers/shadow/fullscreen), e.g. voxelization in a shadow
 * vertex shader or a composite pass reading an SSBO. renderpearl's binding table has no storage resources, so they get
 * Metal indices above the backend's own (the backend leaves SPIR-V bindings outside its table untouched and SPIRV-Cross
 * uses them as MSL indices) and are bound per draw through {@code MetalliumInterop.setDrawBindings}:
 * <ul>
 *   <li>images: {@code [[texture(32 + k)]]} - renderpearl numbers at most 16 textures per pipeline from 0;</li>
 *   <li>SSBOs: {@code [[buffer(30 - k)]]}, at most 5 - the backend numbers uniform buffers, push constants and vertex
 *       buffers from 0, and SPIRV-Cross's buffer-size buffer defaults to 25.</li>
 * </ul>
 * The same resource gets the same index in both stages.
 */
final class StorageBindings {
    static final int FIRST_IMAGE_INDEX = 32;
    static final int MAX_IMAGES = 16;
    private static final int[] BUFFER_INDICES = {30, 29, 28, 27, 26};

    enum Kind { IMAGE, BUFFER }

    /** {@code glBinding}: for SSBOs the Iris {@code bufferObject} index ({@code layout(binding = N)}). */
    record Entry(String name, Kind kind, int glBinding, int metalIndex) {
    }

    /** Which image names have an Iris binding (colorimgN, shadowcolorimgN, custom images). */
    interface Validator {
        boolean knowsImage(String name);

        boolean isCustomSampler(String name);
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int images;
    private int buffers;

    /** Assigns (or reuses) the Metal index of a storage descriptor and writes it into the SPIR-V binding decoration. */
    void assign(final SpvModule.Reflection.Descriptor descriptor, final Kind kind, final @Nullable Validator validator) throws PostProgram.UnsupportedProgramException {
        String name = descriptor.name();
        int glBinding = descriptor.binding();
        String key = kind == Kind.BUFFER ? "buffer:" + glBinding : "image:" + name;
        Entry entry = entries.get(key);
        if (entry == null) {
            if (kind == Kind.IMAGE) {
                if (validator == null || !validator.knowsImage(name)) {
                    throw new PostProgram.UnsupportedProgramException("image '" + name + "' has no Iris binding");
                }
                if (images >= MAX_IMAGES) {
                    throw new PostProgram.UnsupportedProgramException("more than " + MAX_IMAGES + " images");
                }
                entry = new Entry(name, kind, glBinding, FIRST_IMAGE_INDEX + images++);
            } else {
                if (buffers >= BUFFER_INDICES.length) {
                    throw new PostProgram.UnsupportedProgramException("more than " + BUFFER_INDICES.length + " SSBOs in one render program");
                }
                entry = new Entry(name, kind, glBinding, BUFFER_INDICES[buffers++]);
            }
            entries.put(key, entry);
        }
        descriptor.binding(entry.metalIndex);
        descriptor.descriptorSetIndex(0);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }
}
