/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.gl;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;
import java.util.Set;

/**
 * Bytecode step (run from {@link de.nprmedia.nativium.iris.NativiumIrisMixinPlugin}) that makes every static method of
 * vanilla's {@code GlStateManager} start with {@code if (GlGuard.active()) return <GlGuard answer or default>;}.
 * Mixin handler methods merged into the class (names containing {@code $}) are left alone.
 */
public final class GlStateManagerGuard {
    private static final String GUARD = "de/nprmedia/nativium/iris/gl/GlGuard";
    /** name + descriptor -> GlGuard method with the same descriptor. */
    private static final Map<String, String> SPECIAL = Map.ofEntries(
            Map.entry("_getString(I)Ljava/lang/String;", "getString"),
            Map.entry("_getInteger(I)I", "getInteger"),
            Map.entry("_genTexture()I", "newName"),
            Map.entry("_glGenBuffers()I", "newName"),
            Map.entry("_glGenVertexArrays()I", "newName"),
            Map.entry("glGenFramebuffers()I", "newName"),
            Map.entry("glCreateProgram()I", "newName"),
            Map.entry("glCreateShader(I)I", "newShader"),
            Map.entry("_glGetUniformLocation(ILjava/lang/CharSequence;)I", "uniformLocation"),
            Map.entry("glGetShaderInfoLog(II)Ljava/lang/String;", "infoLog"),
            Map.entry("glGetProgramInfoLog(II)Ljava/lang/String;", "infoLog"),
            Map.entry("_glUniform1i(II)V", "uniform1i"));
    private static final Set<String> SKIP = Set.of("<clinit>", "<init>");

    private GlStateManagerGuard() {
    }

    public static int apply(final ClassNode node) {
        int guarded = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0
                    || SKIP.contains(method.name) || method.name.contains("$")) {
                continue;
            }
            method.instructions.insert(guard(method));
            guarded++;
        }
        return guarded;
    }

    private static InsnList guard(final MethodNode method) {
        InsnList list = new InsnList();
        LabelNode proceed = new LabelNode();
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "active", "()Z", false));
        list.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
        Type returnType = Type.getReturnType(method.desc);
        String special = SPECIAL.get(method.name + method.desc);
        if (special != null) {
            int slot = 0;
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                list.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                slot += argument.getSize();
            }
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, special, method.desc, false));
        } else {
            switch (returnType.getSort()) {
                case Type.VOID -> {
                }
                case Type.LONG -> list.add(new InsnNode(Opcodes.LCONST_0));
                case Type.FLOAT -> list.add(new InsnNode(Opcodes.FCONST_0));
                case Type.DOUBLE -> list.add(new InsnNode(Opcodes.DCONST_0));
                case Type.OBJECT, Type.ARRAY -> list.add(new InsnNode(Opcodes.ACONST_NULL));
                default -> list.add(new InsnNode(Opcodes.ICONST_0));
            }
        }
        list.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        list.add(proceed);
        return list;
    }
}
