/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import de.nprmedia.nativium.gl.GlObjects.Buffer;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

/**
 * GL buffer names for buffers the engine owns (Minecraft's {@code GpuBuffer}): on OpenGL a mod reads the buffer's GL
 * handle ({@code GlBuffer.handle()}, often through its own accessor) and binds it as an SSBO, a copy source/destination
 * or a pixel-pack target, e.g. a compute shader filling an indirect-draw buffer that the mod then draws from with
 * renderpearl. On Metal, the retargeted cast hands the mod a stand-in whose handle is a name here; its storage is the
 * engine's Metal buffer, resolved at each use. GPU access only; never released by GL.
 */
final class GlExternalBuffers {
    private final GlContext gl;
    private final Map<Object, Integer> names = new IdentityHashMap<>();

    GlExternalBuffers(final GlContext gl) {
        this.gl = gl;
    }

    /** The GL name of {@code engineBuffer}, or 0 if the device cannot wrap it. */
    int nameOf(final Object engineBuffer, final Predicate<Object> closed) {
        purge(closed);
        Integer existing = names.get(engineBuffer);
        if (existing != null && gl.buffers.get(existing) != null) {
            return existing;
        }
        GlDevice.BufferStore store = gl.device.externalBuffer(engineBuffer);
        if (store == null) {
            MetalGLLog.unsupported("engine buffer " + engineBuffer.getClass().getName() + " has no GL name");
            return 0;
        }
        int name = gl.buffers.reserve();
        Buffer buffer = new Buffer(name);
        buffer.store = store;
        buffer.size = store.size();
        buffer.immutable = true;
        buffer.gpuWritten = true;
        buffer.external = true;
        buffer.storageFlags = org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
        gl.buffers.put(name, buffer);
        names.put(engineBuffer, name);
        return name;
    }

    private void purge(final Predicate<Object> closed) {
        for (Iterator<Map.Entry<Object, Integer>> it = names.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Integer> entry = it.next();
            if (closed.test(entry.getKey())) {
                gl.deleteBuffer(entry.getValue());
                it.remove();
            }
        }
    }
}
