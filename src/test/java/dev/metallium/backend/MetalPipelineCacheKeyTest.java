/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MetalPipelineCacheKeyTest {
    private static String description(final String label, final String address, final String destinationRgb) {
        return "<MTLRenderPipelineDescriptorInternal: " + address + ">\n"
                + "    label = " + label + " \n"
                + "    Sample Mask = 0xffffffffffffffff \n"
                + "    Vertex Function = <_MTLFunctionInternal: " + address + "1>\n"
                + "            name = mtl_vs_0123 \n"
                + "            device = <AGXG15CDevice: 0x74aa818000>\n"
                + "    Fragment Function = <_MTLFunctionInternal: " + address + "2>\n"
                + "            name = mtl_fs_4567 \n"
                + "    Color Attachment 0: \n"
                + "            pixelFormat = MTLPixelFormatRGBA8Unorm \n"
                + "            blending = YES \n"
                + "            RGB   = " + destinationRgb + " \n";
    }

    @Test
    void sameDescriptorSameKeyRegardlessOfLabelAndAddresses() {
        assertEquals(MetalPipelineCache.descriptorKey(description("Pipeline a", "0x1051bd030", "MTLBlendFactorOne")),
                MetalPipelineCache.descriptorKey(description("Pipeline b", "0x99aa00", "MTLBlendFactorOne")));
    }

    @Test
    void differentBlendStateDifferentKey() {
        assertNotEquals(MetalPipelineCache.descriptorKey(description("x", "0x1", "MTLBlendFactorOne")),
                MetalPipelineCache.descriptorKey(description("x", "0x1", "MTLBlendFactorOneMinusSourceAlpha")));
    }

    @Test
    void keyIsSha256Hex() {
        assertEquals(64, MetalPipelineCache.descriptorKey("anything").length());
    }
}
