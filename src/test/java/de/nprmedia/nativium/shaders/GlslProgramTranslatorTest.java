package de.nprmedia.nativium.shaders;

import de.nprmedia.nativium.shaders.GlslProgramTranslator.Program;
import de.nprmedia.nativium.shaders.GlslProgramTranslator.ResourceBinding;
import de.nprmedia.nativium.shaders.GlslProgramTranslator.Stage;
import de.nprmedia.nativium.shaders.GlslProgramTranslator.StageResult;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Iris-style programs (GLSL 330 core, loose uniforms, unlocated varyings) through the Metal translation path. */
class GlslProgramTranslatorTest {
    private static final String COMPOSITE_VS = """
            #version 330 core
            in vec3 iris_Position;
            in vec2 iris_UV0;
            uniform mat4 iris_ModelViewMat;
            uniform mat4 iris_ProjMat;
            out vec2 texcoord;
            void main() {
                gl_Position = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position, 1.0);
                texcoord = iris_UV0;
            }
            """;
    private static final String COMPOSITE_FS = """
            #version 330 core
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            uniform float frameTimeCounter;
            uniform vec3 sunPosition;
            uniform int worldTime;
            in vec2 texcoord;
            layout(location = 0) out vec4 outColor0;
            void main() {
                vec4 color = texture(colortex0, texcoord);
                float depth = texture(depthtex0, texcoord).r;
                outColor0 = color * (0.5 + 0.5 * sin(frameTimeCounter)) + vec4(sunPosition * 1e-6, depth * 1e-6) + float(worldTime) * 1e-9;
            }
            """;
    // Varyings declared in different orders in the two stages: locations must still match by name.
    private static final String GBUFFERS_VS = """
            #version 330 core
            in vec3 iris_Position;
            in vec4 iris_Color;
            in vec2 iris_UV0;
            uniform mat4 iris_ModelViewMat;
            uniform mat4 iris_ProjMat;
            out vec4 glcolor;
            out vec2 lmcoord;
            out vec2 texcoord;
            void main() {
                gl_Position = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position, 1.0);
                glcolor = iris_Color;
                lmcoord = vec2(0.0);
                texcoord = iris_UV0;
            }
            """;
    private static final String GBUFFERS_FS = """
            #version 330 core
            uniform sampler2D gtexture;
            uniform float iris_currentAlphaTest;
            in vec2 texcoord;
            in vec4 glcolor;
            in vec2 lmcoord;
            layout(location = 0) out vec4 outColor0;
            layout(location = 1) out vec4 outColor1;
            void main() {
                vec4 color = texture(gtexture, texcoord) * glcolor;
                if (color.a < iris_currentAlphaTest) discard;
                outColor0 = color;
                outColor1 = vec4(lmcoord, 0.0, 1.0);
            }
            """;
    private static final String COMPUTE = """
            #version 430 core
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(rgba16f) uniform image2D colorimg0;
            layout(std430, binding = 0) buffer SharedData { vec4 exposure; } shared_data;
            uniform float viewWidth;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                vec4 c = imageLoad(colorimg0, p);
                imageStore(colorimg0, p, c * shared_data.exposure + viewWidth * 0.0);
            }
            """;

    /**
     * Desktop GLSL allows {@code sampler} as an identifier (Photon, Bliss: {@code float f(sampler2D sampler, ...)}); the
     * Vulkan GLSL dialect used for SPIR-V reserves it as a type ("unexpected SAMPLER, expecting RIGHT_PAREN"). BSL names a
     * variable {@code new}, which SPIRV-Cross copies into MSL (C++): "expected unqualified-id".
     */
    @Test
    void vulkanReservedWordsAsIdentifiers() throws Exception {
        String fs = """
                #version 330 core
                uniform sampler2D colortex0;
                in vec2 texcoord;
                layout(location = 0) out vec4 outColor0;
                vec4 soft(sampler2D sampler, vec2 coord) {
                    vec2 res = vec2(textureSize(sampler, 0));
                    return texture(sampler, coord + 0.5 / res);
                }
                float samplerShadow = 1.0;
                void main() {
                    bool new = texcoord.x > 0.5; // BSL deferred: a C++ keyword, fatal in MSL
                    float delete = new ? 1.0 : 0.5;
                    outColor0 = soft(colortex0, texcoord) * samplerShadow * delete;
                }
                """;
        Program program = GlslProgramTranslator.translate("prepare", sources(COMPOSITE_VS, fs));
        assertTrue(names(program.stages().get(Stage.FRAGMENT), ResourceBinding.Kind.SAMPLED_TEXTURE).contains("colortex0"));
        compileWithMetalIfAvailable(program);
    }

    @Test
    void compositeProgram() throws Exception {
        Program program = GlslProgramTranslator.translate("composite", sources(COMPOSITE_VS, COMPOSITE_FS));
        StageResult fs = program.stages().get(Stage.FRAGMENT);
        Set<String> uniforms = fs.uniforms().stream().map(GlslProgramTranslator.UniformMember::name).collect(Collectors.toSet());
        assertTrue(uniforms.containsAll(Set.of("frameTimeCounter", "sunPosition", "worldTime")), "uniforms: " + uniforms + " resources: " + fs.resources() + "\n" + fs.msl());
        assertTrue(fs.defaultUniformBlockSize() >= 4 + 12 + 4, "block size " + fs.defaultUniformBlockSize());
        assertTrue(names(fs, ResourceBinding.Kind.SAMPLED_TEXTURE).containsAll(Set.of("colortex0", "depthtex0")), "textures: " + fs.resources());
        assertTrue(program.stages().get(Stage.VERTEX).uniforms().stream().anyMatch(u -> u.name().equals("iris_ProjMat")));
        compileWithMetalIfAvailable(program);
    }

    @Test
    void spirvWithDefaultBlockLayout() throws Exception {
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("composite", sources(COMPOSITE_VS, COMPOSITE_FS));
        try {
            GlslProgramTranslator.SpirvStage vs = stages.get(Stage.VERTEX);
            GlslProgramTranslator.SpirvStage fs = stages.get(Stage.FRAGMENT);
            assertNotNull(vs.defaultBlockName());
            assertEquals(0x07230203, vs.spirv().getInt(0), "SPIR-V magic");
            GlslProgramTranslator.BlockMember proj = member(vs, "iris_ProjMat");
            assertEquals(GlslProgramTranslator.ScalarType.FLOAT, proj.scalar());
            assertEquals(4, proj.columns());
            assertEquals(16, proj.matrixStride());
            GlslProgramTranslator.BlockMember sun = member(fs, "sunPosition");
            assertEquals(3, sun.vectorSize());
            assertEquals(1, sun.columns());
            assertEquals(GlslProgramTranslator.ScalarType.INT, member(fs, "worldTime").scalar());
            assertTrue(fs.defaultBlockSize() >= sun.offset() + 12);
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
    }

    @Test
    void spirvVaryingsMatchByNameWithCommaDeclarations() throws Exception {
        String vs = """
                #version 330 core
                in vec3 Position;
                flat out vec3 upVec, sunVec;
                out vec2 texCoord;
                out float fog[2];
                void main() { upVec = vec3(1.0); sunVec = vec3(0.5); texCoord = Position.xy; fog[0] = 0.0; fog[1] = 1.0; gl_Position = vec4(Position, 1.0); }
                """;
        String fs = """
                #version 330 core
                in vec2 texCoord;
                in float fog[2];
                flat in vec3 sunVec;
                flat in vec3 upVec;
                layout(location = 0) out vec4 color;
                void main() { color = vec4(texCoord, fog[1], 1.0) + vec4(sunVec + upVec, 0.0); }
                """;
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("deferred1", sources(vs, fs));
        try {
            Map<String, Integer> out = locations(stages.get(Stage.VERTEX).spirv(), org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
            Map<String, Integer> in = locations(stages.get(Stage.FRAGMENT).spirv(), org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT);
            assertEquals(out, in);
            assertEquals(4, Set.copyOf(in.values()).size(), "unique locations: " + in);
            // fog[2] occupies two consecutive locations: nothing else may use the second one.
            int fog = in.get("fog");
            assertFalse(in.containsValue(fog + 1), "array slots overlap: " + in);
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
    }

    @Test
    void fragmentInputsMissingInVertexStageStillTranslate() throws Exception {
        String vs = """
                #version 330 core
                in vec3 Position;
                out vec2 texCoord;
                void main() { texCoord = Position.xy; gl_Position = vec4(Position, 1.0); }
                """;
        String fs = """
                #version 330 core
                in vec2 texCoord;
                in vec3 viewVector;
                flat in int mode;
                flat in vec4 tint[2];
                noperspective in float depthFade;
                layout(location = 0) out vec4 color;
                void main() { color = vec4(texCoord, viewVector.x + depthFade, float(mode)) + tint[1]; }
                """;
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("gbuffers_basic", sources(vs, fs));
        try {
            Map<String, Integer> out = locations(stages.get(Stage.VERTEX).spirv(), org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
            Map<String, Integer> in = locations(stages.get(Stage.FRAGMENT).spirv(), org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT);
            assertEquals(Set.of("texCoord", "viewVector", "mode", "tint", "depthFade"), out.keySet());
            assertEquals(out, in);
            assertFalse(in.containsValue(in.get("tint") + 1), "array slots overlap: " + in);
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
    }

    @Test
    void fragmentInputsMissingInVertexStageStillTranslateMatrixAndUint() throws Exception {
        String vs = "#version 330 core\nin vec3 Position;\nvoid main() { gl_Position = vec4(Position, 1.0); }\n";
        String fs = "#version 330 core\nflat in uvec2 ids;\nin mat3 basis;\nlayout(location = 0) out vec4 color;\n"
                + "void main() { color = vec4(basis[1], float(ids.x)); }\n";
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("p", sources(vs, fs));
        stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
    }

    @Test
    void fragmentOutputsWithoutLayoutTakeTheNextFreeLocation() throws Exception {
        String vs = """
                #version 330 core
                in vec3 Position;
                void main() { gl_Position = vec4(Position, 1.0); }
                """;
        // superDuperVanilla composite1 (RENDERTARGETS: 4,5): one explicit location 0, one output without a layout.
        // GL links the second to location 1; shaderc's auto-mapping put both at 0 (MSL: duplicated color(0)).
        String fs = """
                #version 330 core
                out vec4 first;
                layout(location = 0) out vec3 sceneColOut;
                out vec4 temporalDataOut;
                layout(location = 2) out vec4 explicitTwo;
                void main() { sceneColOut = vec3(1.0); temporalDataOut = vec4(0.5); first = vec4(0.25); explicitTwo = vec4(0.0); }
                """;
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("composite1", sources(vs, fs));
        try {
            Map<String, Integer> out = locations(stages.get(Stage.FRAGMENT).spirv(), org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
            assertEquals(Map.of("sceneColOut", 0, "first", 1, "temporalDataOut", 3, "explicitTwo", 2), out);
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
        compileWithMetalIfAvailable(GlslProgramTranslator.translate("composite1", sources(vs, fs)));
    }

    @Test
    void fragmentOutputsCanBeMovedToOtherAttachments() throws Exception {
        String vs = """
                #version 330 core
                in vec3 Position;
                void main() { gl_Position = vec4(Position, 1.0); }
                """;
        // Like Iris's patched gbuffers: DRAWBUFFERS:03 -> iris_FragData0/1 at locations 0/1.
        String fs = """
                #version 330 core
                layout(location = 0) out vec4 iris_FragData0;
                layout(location = 1) out vec4 iris_FragData1;
                void main() { iris_FragData0 = vec4(1.0); iris_FragData1 = vec4(0.5); }
                """;
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("gbuffers_terrain", sources(vs, fs));
        try {
            java.nio.ByteBuffer spirv = stages.get(Stage.FRAGMENT).spirv();
            // Render pass attachments colortex0,1,3: slot 0 (colortex0) -> 0, slot 1 (colortex3) -> 2.
            GlslProgramTranslator.remapOutputLocations(spirv, location -> location == 1 ? 2 : location);
            Map<String, Integer> out = locations(spirv, org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
            assertEquals(Map.of("iris_FragData0", 0, "iris_FragData1", 2), out);
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
    }

    @Test
    void shadowSamplersAreReportedAsDepthCompare() throws Exception {
        String vs = """
                #version 330 core
                in vec3 Position;
                out vec2 uv;
                void main() { gl_Position = vec4(Position, 1.0); uv = Position.xy; }
                """;
        String fs = """
                #version 330 core
                uniform sampler2DShadow shadowtex0;
                uniform sampler2D shadowtex1;
                uniform sampler2D colortex0;
                in vec2 uv;
                layout(location = 0) out vec4 color;
                void main() { color = texture(colortex0, uv) * texture(shadowtex0, vec3(uv, 0.5)) + texture(shadowtex1, uv).r; }
                """;
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("composite", sources(vs, fs));
        try {
            assertEquals(java.util.Set.of("shadowtex0"), GlslProgramTranslator.depthCompareSamplerNames(stages.get(Stage.FRAGMENT).spirv()));
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
    }

    private static Map<String, Integer> locations(java.nio.ByteBuffer spirv, int resourceType) throws Exception {
        java.nio.IntBuffer words = spirv.asIntBuffer();
        Map<String, Integer> result = new java.util.TreeMap<>();
        for (var varying : GlslProgramTranslator.varyingsForTest(spirv, resourceType)) {
            result.put(varying.getKey(), words.get(varying.getValue()));
        }
        return result;
    }

    private static GlslProgramTranslator.BlockMember member(GlslProgramTranslator.SpirvStage stage, String name) {
        return stage.defaultBlock().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + stage.defaultBlock()));
    }

    @Test
    void varyingsMatchByNameAcrossStages() throws Exception {
        Map<Stage, String> located = GlslProgramTranslator.assignVaryingLocations(sources(GBUFFERS_VS, GBUFFERS_FS));
        for (String name : new String[]{"glcolor", "lmcoord", "texcoord"}) {
            String vsDecl = declaration(located.get(Stage.VERTEX), name);
            String fsDecl = declaration(located.get(Stage.FRAGMENT), name);
            assertEquals(location(vsDecl), location(fsDecl), name + ": " + vsDecl + " vs " + fsDecl);
        }
        Program program = GlslProgramTranslator.translate("gbuffers_terrain", sources(GBUFFERS_VS, GBUFFERS_FS));
        assertTrue(program.stages().get(Stage.FRAGMENT).uniforms().stream().anyMatch(u -> u.name().equals("iris_currentAlphaTest")));
        compileWithMetalIfAvailable(program);
    }

    @Test
    void computeProgramWithImagesAndBuffers() throws Exception {
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.COMPUTE, COMPUTE);
        Program program = GlslProgramTranslator.translate("composite_a", sources);
        StageResult cs = program.stages().get(Stage.COMPUTE);
        assertTrue(names(cs, ResourceBinding.Kind.STORAGE_IMAGE).contains("colorimg0"), "images: " + cs.resources());
        assertFalse(names(cs, ResourceBinding.Kind.STORAGE_BUFFER).isEmpty(), "ssbo: " + cs.resources());
        assertTrue(cs.uniforms().stream().anyMatch(u -> u.name().equals("viewWidth")));
        compileWithMetalIfAvailable(program);
    }

    private static final String GL_COMPUTE = """
            #version 460 core
            layout(local_size_x = 128) in;
            layout(binding = 3, std430) restrict buffer Counts { uint total; uint values[]; };
            layout(binding = 1, std140) uniform Settings { uint count; };
            layout(binding = 2, r32f) uniform restrict writeonly image2D target;
            layout(binding = 5) uniform sampler2D depth;
            layout(location = 7) uniform int explicitLocation;
            uniform float loose;
            shared uint scratch[128];
            void main() {
                uint i = gl_LocalInvocationID.x;
                scratch[i] = values[gl_GlobalInvocationID.x];
                barrier();
                memoryBarrierShared();
                if (i == 0u) {
                    atomicAdd(total, scratch[0] + count + uint(explicitLocation));
                    uint old = atomicCompSwap(values[0], 0u, 1u);
                    imageStore(target, ivec2(gl_WorkGroupID.xy), vec4(texelFetch(depth, ivec2(0), 0).r + loose + float(old)));
                }
            }
            """;

    @Test
    void computeWithGlBindingRulesReflectsBindingsAndWorkgroupSize() throws Exception {
        Map<Stage, GlslProgramTranslator.SpirvStage> spirv = GlslProgramTranslator.compileToSpirv("glcompute", Map.of(Stage.COMPUTE, GL_COMPUTE));
        try {
            GlslProgramTranslator.SpirvStage stage = spirv.get(Stage.COMPUTE);
            StageResult cs = GlslProgramTranslator.spirvToMsl(stage, GlslProgramTranslator.MSL_VERSION_3_2, false);
            assertArrayEquals(new int[]{128, 1, 1}, cs.localSize());
            Map<String, Integer> bindings = new java.util.HashMap<>();
            for (ResourceBinding r : cs.resources()) bindings.put(r.name(), r.glBinding());
            assertEquals(3, bindings.get("Counts"), "resources: " + cs.resources());
            assertEquals(1, bindings.get("Settings"), "resources: " + cs.resources());
            assertEquals(2, bindings.get("target"), "resources: " + cs.resources());
            assertEquals(5, bindings.get("depth"), "resources: " + cs.resources());
            Map<String, GlslProgramTranslator.ExplicitLayout> layouts = GlslProgramTranslator.explicitLayouts(
                    GlslProgramTranslator.preprocess("glcompute", Stage.COMPUTE, GL_COMPUTE));
            assertEquals(Map.of("Counts", new GlslProgramTranslator.ExplicitLayout(3, -1), "Settings", new GlslProgramTranslator.ExplicitLayout(1, -1),
                    "target", new GlslProgramTranslator.ExplicitLayout(2, -1), "depth", new GlslProgramTranslator.ExplicitLayout(5, -1),
                    "explicitLocation", new GlslProgramTranslator.ExplicitLayout(-1, 7)), layouts);
            compileWithMetalIfAvailable(new Program("glcompute", Map.of(Stage.COMPUTE, cs)), true);
        } finally {
            spirv.values().forEach(s -> org.lwjgl.system.MemoryUtil.memFree(s.spirv()));
        }
    }

    @Test
    void glInstanceIdExcludesBaseInstanceForGlPrograms() throws Exception {
        String vs = """
                #version 460 core
                flat out int instance;
                void main() { instance = gl_InstanceID; gl_Position = vec4(float(gl_VertexID), 0.0, 0.0, 1.0); }
                """;
        Map<Stage, GlslProgramTranslator.SpirvStage> spirv = GlslProgramTranslator.compileToSpirv("instance", Map.of(Stage.VERTEX, vs));
        try {
            StageResult gl = GlslProgramTranslator.spirvToMsl(spirv.get(Stage.VERTEX), GlslProgramTranslator.MSL_VERSION_3_2, true);
            assertTrue(gl.msl().contains("[[base_instance]]") && gl.msl().contains("gl_InstanceIndex = spvMetalInstanceId - "), gl.msl());
            compileWithMetalIfAvailable(new Program("instance", Map.of(Stage.VERTEX, gl)), true);
            StageResult vulkan = GlslProgramTranslator.spirvToMsl(spirv.get(Stage.VERTEX), GlslProgramTranslator.MSL_VERSION_3_2, false);
            assertFalse(vulkan.msl().contains("spvMetalInstanceId"), "Vulkan-semantics callers are unchanged");
        } finally {
            spirv.values().forEach(s -> org.lwjgl.system.MemoryUtil.memFree(s.spirv()));
        }
    }

    @Test
    void subgroupArithmeticAndClusteredOpsCompileToMsl() throws Exception {
        String source = """
                #version 460 core
                #extension GL_KHR_shader_subgroup_basic : require
                #extension GL_KHR_shader_subgroup_arithmetic : require
                #extension GL_KHR_shader_subgroup_clustered : require
                layout(local_size_x = 64) in;
                layout(binding = 0, std430) buffer Data { uint values[]; };
                layout(binding = 1, std430) buffer Floats { float floats[]; };
                void main() {
                    uint v = values[gl_GlobalInvocationID.x];
                    uint prefix = subgroupExclusiveAdd(v);
                    subgroupBarrier();
                    float m = subgroupClusteredMax(floats[gl_GlobalInvocationID.x], 4);
                    float all = subgroupMax(m);
                    if (gl_SubgroupInvocationID == gl_SubgroupSize - 1u) values[gl_SubgroupID] = prefix + uint(all) + gl_NumSubgroups;
                }
                """;
        Program program = GlslProgramTranslator.translate("subgroups", Map.of(Stage.COMPUTE, source), GlslProgramTranslator.MSL_VERSION_3_2);
        compileWithMetalIfAvailable(program, true);
    }

    @Test
    void vulkanOnlyKeywordsAreValidGlIdentifiers() throws Exception {
        // Photon's bicubic helper: "sampler" is a type keyword only in Vulkan GLSL.
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.FRAGMENT, """
                #version 330 core
                uniform sampler2D colortex0;
                layout(location = 0) out vec4 color;
                vec4 fetch(sampler2D sampler, vec2 coord) { return textureLod(sampler, coord, 0.0); }
                float length_squared(vec3 v) { return dot(v, v); }
                float saturate(float x) { return clamp(x, 0.0, 1.0); }
                float level(float bias) { return bias; }
                void main() { bool new = level(1.0) > 0.0; color = fetch(colortex0, vec2(0.5)) * saturate(length_squared(vec3(new ? 0.5 : 0.25))); }
                """);
        Program program = GlslProgramTranslator.translate("bicubic", sources);
        assertTrue(names(program.stages().get(Stage.FRAGMENT), ResourceBinding.Kind.SAMPLED_TEXTURE).contains("colortex0"));
        compileWithMetalIfAvailable(program);
    }

    @Test
    void ownDefinitionsOfNewerBuiltinsAreRenamed() throws Exception {
        // Bliss (#version 120, raised to 330 by Iris): float tanh(float) and float fma(float, float, float) duplicate
        // built-ins; other overloads must still reach the built-in.
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.FRAGMENT, """
                #version 330 core
                layout(location = 0) out vec4 color;
                float tanh(float x){
                	return (exp(x) - exp(-x))/(exp(x) + exp(-x));
                }
                float fma(float a,float b,float c){ return a * b + c; }
                void main() { color = vec4(tanh(0.5), fma(1.0, 2.0, 3.0), tanh(vec2(0.25)).x, 1.0); }
                """);
        Program program = GlslProgramTranslator.translate("tanh", sources);
        compileWithMetalIfAvailable(program);
        String renamed = GlslProgramTranslator.renameBuiltinRedefinitions("float tanh(float x) { return x; }\nvoid main() { tanh(1.0); tanh(vec3(1.0)); }");
        assertTrue(renamed.contains("float nativium_tanh(float x)"), renamed);
        assertTrue(renamed.contains("vec3 nativium_tanh(vec3 a0) { return tanh(a0); }"), renamed);
        assertTrue(renamed.contains("nativium_tanh(vec3(1.0))"), renamed);
        // A different signature is a legal overload: left alone.
        String overload = "float round(float x, float step) { return x; }\nvoid main() { round(1.0, 2.0); }";
        assertEquals(overload, GlslProgramTranslator.renameBuiltinRedefinitions(overload));
    }

    @Test
    void bindingQualifiersWorkInOldVersionsWithExtensions() throws Exception {
        // Complementary Unbound (colored lighting): #version 330 + SSBO extension + layout(std430, binding = 0).
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.FRAGMENT, """
                #version 330 core
                #extension GL_ARB_shader_storage_buffer_object: enable
                struct blockData { vec4[6] packedFaceData; };
                layout(std430, binding = 0) readonly buffer blockDataSSBO { blockData data[]; };
                layout(location = 0) out vec4 color;
                void main() { color = data[0].packedFaceData[1]; }
                """);
        GlslProgramTranslator.compileToSpirv("ssbo330", sources).values().forEach(stage -> MemoryUtil.memFree(stage.spirv()));
    }

    @Test
    void structVaryingsTakeOneLocationPerMember() throws Exception {
        // Photon: "flat out CloudsParameters clouds_params;" next to plain varyings.
        String common = "struct Params { vec2 coverage; vec2 detail; float height; };\n";
        Map<Stage, GlslProgramTranslator.SpirvStage> stages = GlslProgramTranslator.compileToSpirv("clouds", Map.of(
                Stage.VERTEX, "#version 330 core\n" + common + """
                        in vec3 Position;
                        flat out Params params;
                        out vec2 uv;
                        void main() { params = Params(vec2(1.0), vec2(2.0), 3.0); uv = Position.xy; gl_Position = vec4(Position, 1.0); }
                        """,
                Stage.FRAGMENT, "#version 330 core\n" + common + """
                        flat in Params params;
                        in vec2 uv;
                        layout(location = 0) out vec4 color;
                        void main() { color = vec4(params.coverage + uv, params.detail.x, params.height); }
                        """));
        try {
            var outputs = GlslProgramTranslator.varyingsForTest(stages.get(Stage.VERTEX).spirv(), org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
            java.nio.IntBuffer words = stages.get(Stage.VERTEX).spirv().asIntBuffer();
            Map<String, Integer> locations = new java.util.HashMap<>();
            outputs.forEach(e -> locations.put(e.getKey(), words.get(e.getValue())));
            int params = locations.get("params");
            int uv = locations.get("uv");
            assertTrue(uv >= params + 3 || uv < params, "struct occupies 3 locations: " + locations);
        } finally {
            stages.values().forEach(stage -> org.lwjgl.system.MemoryUtil.memFree(stage.spirv()));
        }
    }

    @Test
    void geometryLikeErrorsAreReported() {
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.FRAGMENT, "#version 330 core\nvoid main() { undefined_call(); }\n");
        GlslProgramTranslator.TranslationException e = assertThrows(GlslProgramTranslator.TranslationException.class,
                () -> GlslProgramTranslator.translate("broken", sources));
        assertTrue(e.getMessage().contains("broken"), e.getMessage());
    }

    private static Map<Stage, String> sources(String vs, String fs) {
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.VERTEX, vs);
        sources.put(Stage.FRAGMENT, fs);
        return sources;
    }

    private static Set<String> names(StageResult stage, ResourceBinding.Kind kind) {
        return stage.resources().stream().filter(r -> r.kind() == kind).map(ResourceBinding::name).collect(Collectors.toSet());
    }

    private static String declaration(String source, String name) {
        return source.lines().filter(l -> l.matches(".*\\b(in|out)\\s+\\w+\\s+" + name + "\\s*;.*")).findFirst().orElseThrow();
    }

    private static int location(String declaration) {
        var m = java.util.regex.Pattern.compile("location\\s*=\\s*(\\d+)").matcher(declaration);
        assertTrue(m.find(), "no location in " + declaration);
        return Integer.parseInt(m.group(1));
    }

    /** On macOS, also hands the generated MSL to the real Metal compiler. */
    private static void compileWithMetalIfAvailable(Program program) {
        compileWithMetalIfAvailable(program, false);
    }

    private static void compileWithMetalIfAvailable(Program program, boolean msl32) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLDevice device = Metal.MTLCreateSystemDefaultDevice();
            assertNotNull(device, "no Metal device");
            for (StageResult stage : program.stages().values()) {
                io.github.kokodio.metaljvm.metal.MTLCompileOptions options = null;
                if (msl32) {
                    options = io.github.kokodio.metaljvm.metal.MTLCompileOptions.alloc().init();
                    options.setLanguageVersion(io.github.kokodio.metaljvm.metal.MTLLanguageVersion.Version3_2);
                }
                MTLLibrary library = device.newLibraryWithSource(stage.msl(), options);
                assertNotNull(library, program.name() + "/" + stage.stage());
                assertNotNull(library.newFunctionWithName(stage.entryPoint()), "entry point " + stage.entryPoint());
                library.release();
            }
        }
    }
}
