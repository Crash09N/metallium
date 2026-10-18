package dev.metallium.shaders;

import dev.metallium.shaders.ComputeTranslator.Kernel;
import dev.metallium.shaders.ComputeTranslator.Kind;
import dev.metallium.shaders.ComputeTranslator.Resource;
import dev.metallium.shaders.GlslProgramTranslator.SpirvStage;
import dev.metallium.shaders.GlslProgramTranslator.Stage;
import io.github.kokodio.metaljvm.metal.MTLComputePipelineState;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Iris-style compute shaders (images, SSBOs with runtime arrays, image atomics, loose uniforms) to MSL kernels. */
class ComputeTranslatorTest {
    private static final String COMPUTE = """
            #version 430 core
            layout(local_size_x = 8, local_size_y = 4) in;
            layout(rgba16f) uniform image2D colorimg3;
            layout(r32ui) uniform uimage3D voxel_img;
            layout(std430, binding = 2) buffer Data { vec4 exposure; uint values[]; } data;
            uniform sampler2D colortex0;
            uniform sampler2D unusedSampler;
            uniform float viewWidth;
            uniform vec3 cameraPosition;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                vec4 c = texelFetch(colortex0, p, 0) * data.exposure + viewWidth * 0.0 + cameraPosition.x * 0.0;
                imageStore(colorimg3, p, c);
                imageAtomicAdd(voxel_img, ivec3(p, 0), 1u);
                if (p.x < data.values.length()) data.values[p.x] = 1u;
            }
            """;

    @Test
    void kernelLayout() throws Exception {
        Map<Stage, SpirvStage> stages = GlslProgramTranslator.compileToSpirv("composite_a", Map.of(Stage.COMPUTE, COMPUTE));
        SpirvStage stage = stages.get(Stage.COMPUTE);
        try {
            Kernel kernel = ComputeTranslator.toMsl(stage);
            assertArrayEquals(new int[]{8, 4, 1}, kernel.localSize());
            Resource block = find(kernel, "gl_DefaultUniformBlock", Kind.UNIFORM_BUFFER);
            assertNotNull(block, "default block: " + kernel.resources());
            assertEquals(0, block.mslIndex());
            assertTrue(block.defaultBlock());
            Resource ssbo = kernel.resources().stream().filter(r -> r.kind() == Kind.STORAGE_BUFFER).findFirst().orElseThrow();
            assertEquals(2, ssbo.glBinding(), "Iris bufferObject index");
            assertEquals(1, ssbo.mslIndex());
            assertEquals(2, kernel.bufferSizeIndex(), "runtime array .length() needs the buffer-size buffer");
            Resource sampled = find(kernel, "colortex0", Kind.SAMPLED_TEXTURE);
            assertEquals(0, sampled.mslIndex());
            assertEquals(0, sampled.mslSampler());
            assertNull(find(kernel, "unusedSampler", Kind.SAMPLED_TEXTURE), "inactive resources are not bound");
            Resource image = find(kernel, "colorimg3", Kind.STORAGE_IMAGE);
            Resource voxels = find(kernel, "voxel_img", Kind.STORAGE_IMAGE);
            assertEquals(2, voxels.dimension(), "3D");
            assertNotEquals(image.mslIndex(), voxels.mslIndex());
            assertTrue(image.mslIndex() >= 1 && voxels.mslIndex() >= 1);
            assertTrue(stage.defaultBlock().stream().anyMatch(m -> m.name().equals("cameraPosition")));
            compileWithMetalIfAvailable(kernel);
        } finally {
            MemoryUtil.memFree(stage.spirv());
        }
    }

    @Test
    void largeSharedVec3ArraysFitThreadgroupMemory() throws Exception {
        // Photon's sky SH reduction: shared vec3[256][9] is 36 KiB as float3 (16-byte stride), over Metal's 32 KiB.
        String source = """
                #version 430 core
                layout(local_size_x = 256) in;
                layout(rgba16f) writeonly uniform image2D colorimg4;
                shared vec3 shared_memory[256][9];
                vec3 evaluate(vec3 sh[9], vec3 n) { return sh[0] + sh[4] * n.y; }
                void main() {
                    uint i = gl_LocalInvocationID.x;
                    for (uint j = 0u; j < 9u; ++j) shared_memory[i][j] = vec3(float(i + j));
                    barrier();
                    for (uint stride = 128u; stride > 0u; stride /= 2u) {
                        if (i < stride) for (uint j = 0u; j < 9u; ++j) shared_memory[i][j] += shared_memory[i + stride][j];
                        barrier();
                    }
                    if (i < 9u) imageStore(colorimg4, ivec2(i, 0), vec4(shared_memory[0][i], 1.0));
                    if (i == 0u) imageStore(colorimg4, ivec2(9, 0), vec4(evaluate(shared_memory[0], vec3(0.0, 1.0, 0.0)), 1.0));
                }
                """;
        Map<Stage, SpirvStage> stages = GlslProgramTranslator.compileToSpirv("deferred4_a", Map.of(Stage.COMPUTE, source));
        SpirvStage stage = stages.get(Stage.COMPUTE);
        try {
            Kernel kernel = ComputeTranslator.toMsl(stage);
            String packed = ComputeTranslator.packThreadgroupVectors(kernel.msl());
            assertTrue(packed.contains("threadgroup spvUnsafeArray<spvUnsafeArray<packed_float3, 9>, 256> shared_memory"), packed);
            compileWithMetalIfAvailable(new Kernel(packed, kernel.entryPoint(), kernel.localSize(), kernel.resources(), kernel.bufferSizeIndex()));
        } finally {
            MemoryUtil.memFree(stage.spirv());
        }
    }

    private static Resource find(Kernel kernel, String nameSuffix, Kind kind) {
        return kernel.resources().stream().filter(r -> r.kind() == kind && r.name().endsWith(nameSuffix)).findFirst().orElse(null);
    }

    private static void compileWithMetalIfAvailable(Kernel kernel) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLDevice device = Metal.MTLCreateSystemDefaultDevice();
            assertNotNull(device, "no Metal device");
            MTLLibrary library = device.newLibraryWithSource(kernel.msl(), null);
            MTLFunction function = library.newFunctionWithName(kernel.entryPoint());
            assertNotNull(function, "entry point " + kernel.entryPoint());
            MTLComputePipelineState state = device.newComputePipelineStateWithFunction(function);
            assertNotNull(state);
            assertTrue(state.maxTotalThreadsPerThreadgroup() >= 32);
            state.release();
            function.release();
            library.release();
        }
    }
}
