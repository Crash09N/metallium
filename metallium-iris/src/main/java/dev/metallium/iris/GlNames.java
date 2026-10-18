package dev.metallium.iris;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic GL-style integer names for Metal objects, so Iris code that keys everything by GL name keeps working. */
public final class GlNames {
    // Start far away from small ids that code may treat specially (0 = none).
    private static final AtomicInteger NEXT = new AtomicInteger(0x4000_0000);
    private static final Int2ObjectOpenHashMap<WeakReference<Object>> OBJECTS = new Int2ObjectOpenHashMap<>();

    private GlNames() {
    }

    public static synchronized int register(final Object object) {
        int name = NEXT.getAndIncrement();
        OBJECTS.put(name, new WeakReference<>(object));
        return name;
    }

    public static synchronized Object lookup(final int name) {
        WeakReference<Object> ref = OBJECTS.get(name);
        return ref != null ? ref.get() : null;
    }
}
