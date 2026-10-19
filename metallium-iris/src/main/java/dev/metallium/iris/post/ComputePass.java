package dev.metallium.iris.post;

import dev.metallium.backend.MetalliumInterop;
import dev.metallium.shaders.ComputeTranslator;
import dev.metallium.shaders.ComputeTranslator.Kind;
import dev.metallium.shaders.ComputeTranslator.Resource;
import dev.metallium.shaders.GlslProgramTranslator;
import dev.metallium.shaders.GlslProgramTranslator.SpirvStage;
import dev.metallium.shaders.GlslProgramTranslator.Stage;
import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLComputeCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLComputePipelineState;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLResourceOptions;
import io.github.kokodio.metaljvm.metal.MTLSamplerState;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.properties.IndirectPointer;
import org.joml.Vector2f;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

/**
 * Stage 5: one Iris compute program ({@code *.csh}) on Metal. Post-Iris GLSL -> SPIR-V (core translator) -> MSL kernel
 * ({@link ComputeTranslator}) -> {@code MTLComputePipelineState}. Work-group counts follow Iris's {@code ComputeProgram}
 * ({@code workGroups} absolute, {@code workGroupsRender} relative to the pass size, else one thread per pixel), or come
 * from an SSBO ({@code indirect.<pass> = <buffer> <offset>}, {@code dispatchThreadgroupsWithIndirectBuffer}).
 */
final class ComputePass implements AutoCloseable {
    /** Supplies the Metal resources a kernel binds; called before the compute encoder is (re)opened. */
    interface Resolver {
        MTLTexture image(Resource resource);

        MTLTexture texture(Resource resource);

        MTLSamplerState sampler(Resource resource);

        @Nullable MTLBuffer storageBuffer(int index);
    }

    final String name;
    final ComputeTranslator.Kernel kernel;
    final PostUniforms.Holder uniforms;
    /** Iris texture stage (selects {@code texture.<stage>.*} custom textures); set by the pipeline after compiling. */
    net.irisshaders.iris.shaderpack.texture.TextureStage stage = net.irisshaders.iris.shaderpack.texture.TextureStage.COMPOSITE_AND_FINAL;
    private final PostUniforms.@Nullable Block block;
    private final MTLComputePipelineState state;
    private final @Nullable Vector3i absoluteWorkGroups;
    private final @Nullable Vector2f relativeWorkGroups;
    private final @Nullable IndirectPointer indirect;
    /** Scratch for the buffer-size buffer ({@code .length()}): one uint per buffer index. */
    private final MemorySegment sizes;
    private final Arena arena = Arena.ofShared();

    private ComputePass(final String name, final ComputeTranslator.Kernel kernel, final PostUniforms.@Nullable Block block, final MTLComputePipelineState state,
                        final ComputeSource source) {
        this.name = name;
        this.kernel = kernel;
        this.block = block;
        this.state = state;
        this.uniforms = new PostUniforms.Holder(name, block != null ? List.of(block) : List.of());
        this.absoluteWorkGroups = source.getWorkGroups();
        this.relativeWorkGroups = source.getWorkGroupRelative();
        this.indirect = source.getIndirectPointer();
        this.sizes = arena.allocate(4L * Math.max(1, kernel.bufferCount() + 1), 16);
    }

    static ComputePass compile(final ComputeSource source, final String glsl, final MetalCompute compute, final java.util.function.Consumer<Resource> validator)
            throws GlslProgramTranslator.TranslationException, PostProgram.UnsupportedProgramException {
        String name = source.getName();
        Map<Stage, SpirvStage> stages = GlslProgramTranslator.compileToSpirv(name, Map.of(Stage.COMPUTE, glsl));
        SpirvStage stage = stages.get(Stage.COMPUTE);
        PostUniforms.Block block = null;
        try {
            ComputeTranslator.Kernel kernel = ComputeTranslator.toMsl(stage);
            dev.metallium.shaders.ShaderDump.write(name, "csh.glsl", glsl);
            dev.metallium.shaders.ShaderDump.write(name, "metal", kernel.msl());
            for (Resource resource : kernel.resources()) {
                if (resource.kind() == Kind.UNIFORM_BUFFER && !resource.defaultBlock()) {
                    throw new PostProgram.UnsupportedProgramException("uniform block '" + resource.name() + "' (explicit UBOs are not supported yet)");
                }
                try {
                    validator.accept(resource);
                } catch (IllegalArgumentException e) {
                    throw new PostProgram.UnsupportedProgramException(e.getMessage());
                }
            }
            if (stage.defaultBlockName() != null) {
                block = new PostUniforms.Block(stage.defaultBlockSize(), stage.defaultBlock());
            }
            MTLComputePipelineState state;
            try {
                state = buildPipeline(compute, kernel.msl(), kernel.entryPoint());
            } catch (NSErrorException e) {
                if (!String.valueOf(e.getMessage()).contains("hreadgroup memory")) {
                    dumpSource(name, glsl, kernel.msl());
                    throw new GlslProgramTranslator.TranslationException(name + ": MSL compile failed: " + e.getMessage());
                }
                // GL packs size shared arrays with 12-byte vec3s; pack them for Metal's 32 KiB limit.
                try {
                    state = buildPipeline(compute, ComputeTranslator.packThreadgroupVectors(kernel.msl()), kernel.entryPoint());
                } catch (NSErrorException again) {
                    dumpSource(name, glsl, ComputeTranslator.packThreadgroupVectors(kernel.msl()));
                    throw new GlslProgramTranslator.TranslationException(name + ": MSL compile failed: " + again.getMessage());
                }
            }
            long threads = (long) kernel.localSize()[0] * kernel.localSize()[1] * kernel.localSize()[2];
            if (threads > state.maxTotalThreadsPerThreadgroup()) {
                state.release();
                throw new PostProgram.UnsupportedProgramException("local size " + threads + " exceeds Metal's " + state.maxTotalThreadsPerThreadgroup() + " threads per threadgroup");
            }
            ComputePass pass = new ComputePass(name, kernel, block, state, source);
            block = null; // owned by the pass
            return pass;
        } finally {
            if (block != null) {
                block.free();
            }
            MemoryUtil.memFree(stage.spirv());
        }
    }

    /** Writes the post-Iris GLSL and the generated MSL of a kernel Metal rejected to {@code <game dir>/metallium-debug/}. */
    private static void dumpSource(final String name, final String glsl, final String msl) {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("metallium-debug");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(name.replace('/', '_') + ".csh.glsl"), glsl);
            java.nio.file.Files.writeString(dir.resolve(name.replace('/', '_') + ".metal"), msl);
        } catch (java.io.IOException | RuntimeException e) {
            dev.metallium.iris.MetalliumIris.LOGGER.debug("[metallium-iris] could not write debug sources for {}", name, e);
        }
    }

    private static MTLComputePipelineState buildPipeline(final MetalCompute compute, final String msl, final String entryPoint) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLLibrary library = compute.device.newLibraryWithSource(msl, null);
            try {
                return compute.pipeline(library, entryPoint);
            } finally {
                library.release();
            }
        }
    }

    /** Iris's {@code ComputeProgram.getWorkGroups}; {@code width/height} = the pass size (main target or shadow map). */
    Vector3i workGroups(final float width, final float height) {
        int[] local = kernel.localSize();
        if (absoluteWorkGroups != null) {
            return absoluteWorkGroups;
        }
        if (relativeWorkGroups != null) {
            return new Vector3i((int) Math.ceil(Math.ceil(width * relativeWorkGroups.x) / local[0]),
                    (int) Math.ceil(Math.ceil(height * relativeWorkGroups.y) / local[1]), 1);
        }
        return new Vector3i((int) Math.ceil(width / local[0]), (int) Math.ceil(height / local[1]), 1);
    }

    /** Encodes the dispatch; uniforms must be updated (and custom uniforms pushed) by the caller. */
    void dispatch(final MetalCompute compute, final Resolver resolver, final float width, final float height) {
        List<Resource> resources = kernel.resources();
        Object[] bound = new Object[resources.size()];
        MTLSamplerState[] samplers = new MTLSamplerState[resources.size()];
        for (int i = 0; i < resources.size(); i++) {
            Resource r = resources.get(i);
            switch (r.kind()) {
                case STORAGE_IMAGE -> bound[i] = resolver.image(r);
                case SAMPLED_TEXTURE -> {
                    bound[i] = resolver.texture(r);
                    samplers[i] = resolver.sampler(r);
                }
                case STORAGE_BUFFER -> bound[i] = resolver.storageBuffer(r.glBinding());
                case UNIFORM_BUFFER -> bound[i] = null;
            }
        }
        MTLBuffer indirectBuffer = null;
        if (indirect != null) {
            indirectBuffer = resolver.storageBuffer(indirect.buffer());
            if (indirectBuffer == null) {
                throw new IllegalStateException(name + ": indirect dispatch buffer " + indirect.buffer() + " does not exist");
            }
        }

        MTLComputeCommandEncoder encoder = compute.computeEncoder();
        encoder.setComputePipelineState(state);
        if (block != null) {
            uploadBlock(encoder, block.data);
        }
        for (int i = 0; i < resources.size(); i++) {
            Resource r = resources.get(i);
            switch (r.kind()) {
                case STORAGE_IMAGE -> encoder.setTexture((MTLTexture) bound[i], r.mslIndex());
                case SAMPLED_TEXTURE -> {
                    encoder.setTexture((MTLTexture) bound[i], r.mslIndex());
                    encoder.setSamplerState(samplers[i], r.mslSampler());
                }
                case STORAGE_BUFFER -> {
                    MTLBuffer buffer = (MTLBuffer) bound[i];
                    if (buffer == null) {
                        // GL: an unbound SSBO reads zero; bind a scratch zero buffer of the minimal size.
                        buffer = compute.zeroBuffer();
                    }
                    encoder.setBuffer(buffer, 0, r.mslIndex());
                    sizes.setAtIndex(ValueLayout.JAVA_INT, r.mslIndex(), (int) Math.min(Integer.MAX_VALUE, buffer.length()));
                }
                case UNIFORM_BUFFER -> {
                }
            }
        }
        if (kernel.bufferSizeIndex() >= 0) {
            encoder.setBytes(sizes, 4L * (kernel.bufferCount() + 1), kernel.bufferSizeIndex());
        }
        int[] local = kernel.localSize();
        MTLSize threadsPerGroup = new MTLSize(local[0], local[1], local[2]);
        if (indirectBuffer != null) {
            encoder.dispatchThreadgroupsWithIndirectBuffer(indirectBuffer, indirect.offset(), threadsPerGroup);
        } else {
            Vector3i groups = workGroups(width, height);
            if (groups.x <= 0 || groups.y <= 0 || groups.z <= 0) {
                return;
            }
            encoder.dispatchThreadgroups(new MTLSize(groups.x, groups.y, groups.z), threadsPerGroup);
        }
        compute.countDispatch();
    }

    private static void uploadBlock(final MTLComputeCommandEncoder encoder, final java.nio.ByteBuffer data) {
        MemorySegment segment = MemorySegment.ofBuffer(data.duplicate().clear());
        if (segment.byteSize() <= 4096) {
            encoder.setBytes(segment, segment.byteSize(), 0);
            return;
        }
        // setBytes is limited to 4 KiB: larger default blocks go through a transient buffer.
        MTLBuffer buffer = MetalliumInterop.device().newBuffer(segment.byteSize(), MTLResourceOptions.StorageModeShared | MTLResourceOptions.HazardTrackingModeUntracked);
        MemorySegment.copy(segment, 0, buffer.contents().reinterpret(segment.byteSize()), 0, segment.byteSize());
        encoder.setBuffer(buffer, 0, 0);
        MetalliumInterop.releaseAfterGpu(buffer::release);
    }

    @Override
    public void close() {
        uniforms.release();
        MTLComputePipelineState released = state;
        MetalliumInterop.releaseAfterGpu(released::release);
        if (block != null) {
            block.free();
        }
        arena.close();
    }
}
