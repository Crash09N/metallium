/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MetalCrossShaderCacheKeyTest {
    private static final MetalCrossShaderCompiler.MetalIndices INDICES = new MetalCrossShaderCompiler.MetalIndices(new int[] {0, 1, 0}, 2);

    private static ByteBuffer spirv(final int... words) {
        ByteBuffer buffer = ByteBuffer.allocate(words.length * 4);
        for (int word : words) {
            buffer.putInt(word);
        }
        return buffer.flip();
    }

    private static GpuFormat anyFormat(final int ordinal) {
        return GpuFormat.values()[ordinal];
    }

    @Test
    void keyCoversEveryTranslationInput() {
        ByteBuffer code = spirv(0x07230203, 1, 2, 3);
        String base = MetalCrossShaderCompiler.mslCacheKey(code, INDICES, Map.of(), true);
        assertEquals(base, MetalCrossShaderCompiler.mslCacheKey(spirv(0x07230203, 1, 2, 3), INDICES, Map.of(), true));
        assertNotEquals(base, MetalCrossShaderCompiler.mslCacheKey(spirv(0x07230203, 1, 2, 4), INDICES, Map.of(), true), "SPIR-V");
        assertNotEquals(base, MetalCrossShaderCompiler.mslCacheKey(code, INDICES, Map.of(), false), "frag depth");
        assertNotEquals(base, MetalCrossShaderCompiler.mslCacheKey(code,
                new MetalCrossShaderCompiler.MetalIndices(new int[] {0, 0, 1}, 2), Map.of(), true), "binding remap");
        assertNotEquals(base, MetalCrossShaderCompiler.mslCacheKey(code,
                new MetalCrossShaderCompiler.MetalIndices(new int[] {0, 1, 0}, 3), Map.of(), true), "push constant slot");
        String withAttribute = MetalCrossShaderCompiler.mslCacheKey(code, INDICES, Map.of(0, anyFormat(0)), true);
        assertNotEquals(base, withAttribute, "vertex attribute formats");
        assertNotEquals(withAttribute, MetalCrossShaderCompiler.mslCacheKey(code, INDICES, Map.of(0, anyFormat(1)), true));
        assertNotEquals(withAttribute, MetalCrossShaderCompiler.mslCacheKey(code, INDICES, Map.of(1, anyFormat(0)), true));
        assertEquals(4 * 4, code.remaining(), "key computation leaves the module untouched");
    }

    @Test
    void attributeOrderDoesNotMatter() {
        ByteBuffer code = spirv(0x07230203, 9);
        Map<Integer, GpuFormat> a = new java.util.LinkedHashMap<>();
        a.put(2, anyFormat(0));
        a.put(0, anyFormat(1));
        Map<Integer, GpuFormat> b = new java.util.LinkedHashMap<>();
        b.put(0, anyFormat(1));
        b.put(2, anyFormat(0));
        assertEquals(MetalCrossShaderCompiler.mslCacheKey(code, INDICES, a, true), MetalCrossShaderCompiler.mslCacheKey(code, INDICES, b, true));
    }

    @Test
    void entryPointNamesAreUniquePerStageAndValidIdentifiers() {
        String key = MetalCrossShaderCompiler.mslCacheKey(spirv(0x07230203, 5), INDICES, Map.of(), true);
        String vertex = MetalCrossShaderCompiler.entryPointName(key, true);
        String fragment = MetalCrossShaderCompiler.entryPointName(key, false);
        assertNotEquals(vertex, fragment);
        assertTrue(vertex.matches("[A-Za-z_][A-Za-z0-9_]*"), vertex);
        assertTrue(fragment.matches("[A-Za-z_][A-Za-z0-9_]*"), fragment);
    }

    @Test
    void mslShaderEncodingRoundTripsAndRejectsGarbage() {
        MetalCrossShaderCompiler.MslShader shader = new MetalCrossShaderCompiler.MslShader(
                "#include <metal_stdlib>\nvertex float4 mtl_vs_x() { return 0; } // äöü", true, Set.of("DynamicTransforms", "Sampler0"));
        byte[] encoded = shader.encode();
        assertEquals(shader, MetalCrossShaderCompiler.MslShader.decode(encoded));
        assertNull(MetalCrossShaderCompiler.MslShader.decode(new byte[] {0, 0, 0, 1, 0x7f, 0, 0, 0}));
        assertNull(MetalCrossShaderCompiler.MslShader.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        assertNull(MetalCrossShaderCompiler.MslShader.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1)));
        assertNull(MetalCrossShaderCompiler.MslShader.decode(new byte[0]));
    }

    @Test
    void variantKeyDependsOnPipelineAndLayout() {
        String a = MetalPipelineCache.variantKey("minecraft:pipeline/solid", "mtl_vs_a", "mtl_fs_b", 0x101);
        assertEquals(a, MetalPipelineCache.variantKey("minecraft:pipeline/solid", "mtl_vs_a", "mtl_fs_b", 0x101));
        assertNotEquals(a, MetalPipelineCache.variantKey("minecraft:pipeline/solid", "mtl_vs_a", "mtl_fs_b", 0x103));
        assertNotEquals(a, MetalPipelineCache.variantKey("minecraft:pipeline/solid", "mtl_vs_a", "mtl_fs_c", 0x101));
        assertNotEquals(a, MetalPipelineCache.variantKey("minecraft:pipeline/cutout", "mtl_vs_a", "mtl_fs_b", 0x101));
    }
}
