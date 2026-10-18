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
