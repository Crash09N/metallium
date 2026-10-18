package dev.metallium.smoketest;

import io.github.kokodio.metaljvm.metal.*;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Standalone Metal smoke test for CI and local checks: creates the system device, compiles MSL at runtime,
 * renders a full-screen triangle into an offscreen texture and verifies the read-back pixel.
 * Exit code 0 = Metal works, 1 = failure, 2 = no Metal device.
 */
public final class MetalSmokeTest {
    private static final String MSL = """
            #include <metal_stdlib>
            using namespace metal;
            struct VOut { float4 pos [[position]]; };
            vertex VOut vs(uint vid [[vertex_id]]) {
                float2 p = float2((vid << 1) & 2, vid & 2);
                VOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;
            }
            fragment float4 fs() { return float4(1.0, 0.5, 0.25, 1.0); }
            """;

    public static void main(String[] args) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLDevice device = Metal.MTLCreateSystemDefaultDevice();
            if (device == null) {
                System.out.println("SMOKE: no Metal device");
                System.exit(2);
            }
            System.out.println("SMOKE: device = " + device.name());
            for (MTLGPUFamily family : new MTLGPUFamily[]{MTLGPUFamily.Apple7, MTLGPUFamily.Apple9, MTLGPUFamily.Mac2, MTLGPUFamily.Metal3}) {
                System.out.println("SMOKE: supportsFamily(" + family + ") = " + device.supportsFamily(family));
            }

            MTLLibrary library = device.newLibraryWithSource(MSL, null);
            MTLRenderPipelineDescriptor pipelineDescriptor = MTLRenderPipelineDescriptor.alloc().init();
            pipelineDescriptor.setVertexFunction(library.newFunctionWithName("vs"));
            pipelineDescriptor.setFragmentFunction(library.newFunctionWithName("fs"));
            pipelineDescriptor.colorAttachments().objectAtIndexedSubscript(0).setPixelFormat(MTLPixelFormat.RGBA8Unorm);
            MTLRenderPipelineState pipeline = device.newRenderPipelineState(pipelineDescriptor);

            MTLTextureDescriptor textureDescriptor = MTLTextureDescriptor.texture2DDescriptor(MTLPixelFormat.RGBA8Unorm, 4, 4, false);
            textureDescriptor.setUsage(MTLTextureUsage.RenderTarget);
            textureDescriptor.setStorageMode(MTLStorageMode.Shared);
            MTLTexture texture = device.newTexture(textureDescriptor);

            MTLRenderPassDescriptor pass = MTLRenderPassDescriptor.renderPassDescriptor();
            MTLRenderPassColorAttachmentDescriptor color = pass.colorAttachments().objectAtIndexedSubscript(0);
            color.setTexture(texture);
            color.setLoadAction(MTLLoadAction.Clear);
            color.setStoreAction(MTLStoreAction.Store);
            color.setClearColor(new MTLClearColor(0, 0, 0, 1));

            MTLCommandQueue queue = device.newCommandQueue();
            MTLCommandBuffer commandBuffer = queue.commandBuffer();
            MTLRenderCommandEncoder encoder = commandBuffer.renderCommandEncoder(pass);
            encoder.setRenderPipelineState(pipeline);
            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3);
            encoder.endEncoding();
            commandBuffer.commit();
            commandBuffer.waitUntilCompleted();
            System.out.println("SMOKE: command buffer status = " + commandBuffer.status());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pixels = arena.allocate(4 * 4 * 4);
                texture.getBytes(pixels, 16, new MTLRegion(new MTLOrigin(0, 0, 0), new MTLSize(4, 4, 1)), 0);
                int r = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, 0));
                int g = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, 1));
                int b = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, 2));
                System.out.println("SMOKE: pixel = " + r + "," + g + "," + b);
                boolean ok = r == 255 && Math.abs(g - 128) <= 1 && Math.abs(b - 64) <= 1;
                System.out.println(ok ? "SMOKE: PASS" : "SMOKE: FAIL (unexpected pixel)");
                System.exit(ok ? 0 : 1);
            }
        } catch (Throwable t) {
            System.out.println("SMOKE: FAIL " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
