package dev.metallium.shaders;

import dev.metallium.shaders.GlslProgramTranslator.Program;
import dev.metallium.shaders.GlslProgramTranslator.ResourceBinding;
import dev.metallium.shaders.GlslProgramTranslator.Stage;
import dev.metallium.shaders.GlslProgramTranslator.StageResult;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import org.junit.jupiter.api.Test;

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
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLDevice device = Metal.MTLCreateSystemDefaultDevice();
            assertNotNull(device, "no Metal device");
            for (StageResult stage : program.stages().values()) {
                MTLLibrary library = device.newLibraryWithSource(stage.msl(), null);
                assertNotNull(library, program.name() + "/" + stage.stage());
                assertNotNull(library.newFunctionWithName(stage.entryPoint()), "entry point " + stage.entryPoint());
                library.release();
            }
        }
    }
}
