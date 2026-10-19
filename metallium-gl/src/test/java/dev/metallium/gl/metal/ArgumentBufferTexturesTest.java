/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Text rewrite of SPIRV-Cross entry points for ICB pipelines (no device needed). */
class ArgumentBufferTexturesTest {
    private static final String FRAGMENT = """
            #include <metal_stdlib>
            using namespace metal;

            struct main0_out { float4 fragColor [[color(0)]]; };
            struct main0_in { uint quad [[user(locn0)]]; };

            static inline float4 shade(texture2d<float> t, sampler s) { return t.sample(s, float2(0.5)); }

            [[early_fragment_tests]]
            fragment main0_out main0(main0_in in [[stage_in]], const device Colors& _20 [[buffer(1)]], constant Ubo& _30 [[buffer(3)]],
                    texture2d<float> tint [[texture(3)]], depth2d<float> depthTex [[texture(1)]], sampler tintSmplr [[sampler(3)]],
                    sampler depthTexSmplr [[sampler(1)]], texture2d<float, access::read_write> img [[texture(4)]], float4 gl_FragCoord [[position]])
            {
                main0_out out = {};
                out.fragColor = shade(tint, tintSmplr) * depthTex.sample(depthTexSmplr, float2(0.5));
                return out;
            }
            """;

    @Test
    void movesTexturesAndSamplersIntoOneTable() {
        ArgumentBufferTextures.Rewritten r = ArgumentBufferTextures.rewrite(FRAGMENT, "main0");
        assertNotNull(r);
        assertEquals(4, r.bufferIndex(), "after the highest buffer index in the signature");
        assertEquals(List.of(new ArgumentBufferTextures.Member(false, 3), new ArgumentBufferTextures.Member(false, 1),
                new ArgumentBufferTextures.Member(true, 3), new ArgumentBufferTextures.Member(true, 1),
                new ArgumentBufferTextures.Member(false, 4)), r.members());
        assertEquals(40, r.size());
        assertFalse(r.msl().contains("[[texture("), r.msl());
        assertFalse(r.msl().contains("[[sampler("), r.msl());
        assertTrue(r.msl().contains("constant MlTextureTable& mlTextureTable [[buffer(4)]]"), r.msl());
        assertTrue(r.msl().contains("texture2d<float, access::read_write> img = mlTextureTable.img;"), r.msl());
        assertTrue(r.msl().contains("float4 gl_FragCoord [[position]], constant MlTextureTable&"), r.msl());
        // The struct precedes the entry point's attribute line, the helper above stays untouched.
        assertTrue(r.msl().indexOf("struct MlTextureTable") < r.msl().indexOf("[[early_fragment_tests]]"), r.msl());
        assertTrue(r.msl().contains("static inline float4 shade(texture2d<float> t, sampler s)"), r.msl());
    }

    @Test
    void stagesWithoutTexturesOrWithTextureArraysAreLeftAlone() {
        assertNull(ArgumentBufferTextures.rewrite("vertex main0_out main0(const device Q& q [[buffer(0)]], uint vid [[vertex_id]])\n{\n}\n", "main0"));
        assertNull(ArgumentBufferTextures.rewrite("fragment main0_out main0(array<texture2d<float>, 4> t [[texture(0)]], sampler s [[sampler(0)]])\n{\n}\n", "main0"));
    }
}
