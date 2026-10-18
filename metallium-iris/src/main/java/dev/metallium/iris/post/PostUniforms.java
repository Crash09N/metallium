package dev.metallium.iris.post;

import dev.metallium.shaders.GlslProgramTranslator.BlockMember;
import dev.metallium.shaders.GlslProgramTranslator.ScalarType;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Loose GLSL uniforms on Metal. Each translated stage has a CPU copy of its folded default uniform block
 * ({@link Block}); Iris's uniform objects ({@code FloatUniform}, custom/cached uniforms, ...) keep calling
 * {@code IrisRenderSystem.uniform*} / {@code glUniform*} with an int location, which the adapter's mixins forward here.
 * Locations are synthetic and global, so a write needs no "current program": each location maps to the block members
 * (one per stage that declares the uniform) it updates.
 */
public final class PostUniforms {
    private static final Int2ObjectOpenHashMap<Target[]> LOCATIONS = new Int2ObjectOpenHashMap<>();
    private static int nextLocation = 1;

    private PostUniforms() {
    }

    /** CPU shadow of one stage's default uniform block. */
    public static final class Block {
        final ByteBuffer data;
        final List<BlockMember> members;

        Block(final int size, final List<BlockMember> members) {
            this.data = MemoryUtil.memCalloc(Math.max(16, (size + 15) & ~15)).order(ByteOrder.nativeOrder());
            this.members = members;
        }

        BlockMember find(final String name) {
            for (BlockMember member : members) {
                if (member.name().equals(name)) {
                    return member;
                }
            }
            return null;
        }

        void free() {
            MemoryUtil.memFree(data);
        }
    }

    private record Target(Block block, BlockMember member) {
    }

    static synchronized int register(final List<Target> targets) {
        int location = nextLocation++;
        LOCATIONS.put(location, targets.toArray(Target[]::new));
        return location;
    }

    private static synchronized Target[] targets(final int location) {
        return LOCATIONS.get(location);
    }

    /** True if the location belongs to a Metal post program (otherwise the call is not ours). */
    public static boolean owns(final int location) {
        return targets(location) != null;
    }

    public static void setFloats(final int location, final float... values) {
        Target[] targets = targets(location);
        if (targets == null) {
            return;
        }
        for (Target target : targets) {
            BlockMember m = target.member;
            int count = Math.min(values.length, m.vectorSize() * Math.max(1, m.arraySize()));
            for (int i = 0; i < count; i++) {
                int offset = elementOffset(m, i);
                if (m.scalar() == ScalarType.FLOAT) {
                    target.block.data.putFloat(offset, values[i]);
                } else {
                    target.block.data.putInt(offset, (int) values[i]);
                }
            }
        }
    }

    public static void setInts(final int location, final int... values) {
        Target[] targets = targets(location);
        if (targets == null) {
            return;
        }
        for (Target target : targets) {
            BlockMember m = target.member;
            int count = Math.min(values.length, m.vectorSize() * Math.max(1, m.arraySize()));
            for (int i = 0; i < count; i++) {
                int offset = elementOffset(m, i);
                if (m.scalar() == ScalarType.FLOAT) {
                    target.block.data.putFloat(offset, values[i]);
                } else {
                    target.block.data.putInt(offset, values[i]);
                }
            }
        }
    }

    /** Column-major matrix with {@code rows} rows per column (GL convention, transpose = false). */
    public static void setMatrix(final int location, final int rows, final float[] columnMajor) {
        Target[] targets = targets(location);
        if (targets == null) {
            return;
        }
        for (Target target : targets) {
            BlockMember m = target.member;
            int columns = Math.min(m.columns(), columnMajor.length / rows);
            for (int c = 0; c < columns; c++) {
                for (int r = 0; r < Math.min(rows, m.vectorSize()); r++) {
                    target.block.data.putFloat(m.offset() + c * m.matrixStride() + r * 4, columnMajor[c * rows + r]);
                }
            }
        }
    }

    private static int elementOffset(final BlockMember m, final int component) {
        if (m.arraySize() > 0) {
            int vector = Math.max(1, m.vectorSize());
            return m.offset() + (component / vector) * m.arrayStride() + (component % vector) * 4;
        }
        return m.offset() + component * 4;
    }

    /**
     * Iris uniform holder for one Metal post program: resolves names against the program's stage blocks and keeps the
     * uniform objects so they can be updated before each draw.
     */
    public static final class Holder implements DynamicLocationalUniformHolder {
        private final String programName;
        private final List<Block> blocks;
        private final List<Uniform> uniforms = new ArrayList<>();
        private final List<Integer> locations = new ArrayList<>();

        Holder(final String programName, final List<Block> blocks) {
            this.programName = programName;
            this.blocks = blocks;
        }

        @Override
        public OptionalInt location(final String name, final UniformType type) {
            List<Target> targets = new ArrayList<>();
            for (Block block : blocks) {
                BlockMember member = block.find(name);
                if (member != null && compatible(member, type)) {
                    targets.add(new Target(block, member));
                }
            }
            if (targets.isEmpty()) {
                return OptionalInt.empty();
            }
            int location = register(targets);
            locations.add(location);
            return OptionalInt.of(location);
        }

        private static boolean compatible(final BlockMember member, final UniformType type) {
            boolean floating = member.scalar() == ScalarType.FLOAT;
            return switch (type) {
                case FLOAT -> floating && member.vectorSize() == 1 && member.columns() == 1;
                case INT -> !floating && member.scalar() != ScalarType.OTHER && member.vectorSize() == 1;
                case VEC2 -> floating && member.vectorSize() == 2 && member.columns() == 1;
                case VEC3 -> floating && member.vectorSize() == 3 && member.columns() == 1;
                case VEC4 -> floating && member.vectorSize() == 4 && member.columns() == 1;
                case VEC2I -> !floating && member.vectorSize() == 2;
                case VEC3I -> !floating && member.vectorSize() == 3;
                case VEC4I -> !floating && member.vectorSize() == 4;
                case MAT3 -> floating && member.columns() == 3;
                case MAT4 -> floating && member.columns() == 4;
            };
        }

        @Override
        public LocationalUniformHolder addUniform(final UniformUpdateFrequency updateFrequency, final Uniform uniform) {
            uniforms.add(uniform);
            return this;
        }

        @Override
        public DynamicLocationalUniformHolder addDynamicUniform(final Uniform uniform, final ValueUpdateNotifier notifier) {
            uniforms.add(uniform);
            return this;
        }

        @Override
        public net.irisshaders.iris.gl.uniform.UniformHolder externallyManagedUniform(final String name, final UniformType type) {
            return this; // only a diagnostics hint in Iris
        }

        /** Re-evaluates every uniform object (they only write when their value changed). */
        void update() {
            for (Uniform uniform : uniforms) {
                uniform.update();
            }
        }

        void release() {
            synchronized (PostUniforms.class) {
                locations.forEach(LOCATIONS::remove);
            }
        }

        @Override
        public String toString() {
            return "PostUniforms.Holder[" + programName + "]";
        }
    }
}
