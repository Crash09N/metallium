/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.metallium.compat.GlUsageScanner;
import dev.metallium.compat.ModScan;
import dev.metallium.gl.retarget.GlCallRetargeter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Static OpenGL coverage scan of a mod corpus (developer tool, {@code ./gradlew :metallium-gl:glCoverage}).
 *
 * <p>For every jar (and its jar-in-jar nested jars) it classifies the mod the way Metallium does at start-up
 * ({@link GlUsageScanner}: raw GL / Minecraft GL backend / native) and then resolves every GL call site exactly as
 * {@link GlCallRetargeter} would: implemented in metallium-gl or not. Minecraft-backend references that the retargeter
 * handles (static {@code GlStateManager._x} calls in routed classes, {@code ((GlTexture) t).glId()}) are separated from
 * those it cannot translate (subclassing, constructing or calling other backend classes).
 *
 * <p>Arguments: {@code <out.json> <jar-or-dir>...}. Writes a JSON report; {@code tools/gl-coverage-report.py} renders the markdown.
 */
public final class GlCoverageScan {
    private static final String LWJGL_GL = "org/lwjgl/opengl/";
    private static final String BACKEND = "com/mojang/renderpearl/backend/opengl/";
    private static final String STATE_MANAGER = BACKEND + "GlStateManager";
    private static final Set<String> GL_TEXTURE_CLASSES = Set.of(BACKEND + "GlTexture", BACKEND + "GlTextureView");

    /** Integer parameters whose constant values are recorded (function name → parameter indices). */
    static final Map<String, int[]> ENUM_PARAMS = Map.ofEntries(
            Map.entry("glEnable", new int[]{0}), Map.entry("glDisable", new int[]{0}), Map.entry("glIsEnabled", new int[]{0}),
            Map.entry("glEnablei", new int[]{0}), Map.entry("glDisablei", new int[]{0}),
            Map.entry("glBindTexture", new int[]{0}), Map.entry("glTexParameteri", new int[]{0, 1, 2}), Map.entry("glTexParameterf", new int[]{0, 1}),
            Map.entry("glTexParameteriv", new int[]{0, 1}), Map.entry("glTexParameterfv", new int[]{0, 1}),
            Map.entry("glTexImage2D", new int[]{0, 2, 6, 7}), Map.entry("glTexSubImage2D", new int[]{0, 6, 7}),
            Map.entry("glTexImage1D", new int[]{0, 2}), Map.entry("glTexImage3D", new int[]{0, 2}), Map.entry("glTexStorage2D", new int[]{0, 2}),
            Map.entry("glCopyTexSubImage2D", new int[]{0}), Map.entry("glCopyTexImage2D", new int[]{0, 2}), Map.entry("glGenerateMipmap", new int[]{0}),
            Map.entry("glGetTexImage", new int[]{0, 2, 3}), Map.entry("glGetTexLevelParameteri", new int[]{0, 2}),
            Map.entry("glBindBuffer", new int[]{0}), Map.entry("glBufferData", new int[]{0, 2}), Map.entry("glBindBufferBase", new int[]{0}),
            Map.entry("glMapBuffer", new int[]{0, 1}), Map.entry("glMapBufferRange", new int[]{0}),
            Map.entry("glGetInteger", new int[]{0}), Map.entry("glGetIntegerv", new int[]{0}), Map.entry("glGetFloat", new int[]{0}),
            Map.entry("glGetFloatv", new int[]{0}), Map.entry("glGetBoolean", new int[]{0}), Map.entry("glGetBooleanv", new int[]{0}),
            Map.entry("glGetString", new int[]{0}), Map.entry("glGetDouble", new int[]{0}), Map.entry("glGetDoublev", new int[]{0}),
            Map.entry("glDrawArrays", new int[]{0}), Map.entry("glDrawElements", new int[]{0, 2}), Map.entry("glDrawArraysInstanced", new int[]{0}),
            Map.entry("glDrawElementsInstanced", new int[]{0, 2}), Map.entry("glPixelStorei", new int[]{0}),
            Map.entry("glBlendFunc", new int[]{0, 1}), Map.entry("glBlendFuncSeparate", new int[]{0, 1, 2, 3}), Map.entry("glBlendEquation", new int[]{0}),
            Map.entry("glFramebufferTexture2D", new int[]{0, 1, 2}), Map.entry("glBindFramebuffer", new int[]{0}),
            Map.entry("glFramebufferRenderbuffer", new int[]{0, 1}), Map.entry("glRenderbufferStorage", new int[]{0, 1}),
            Map.entry("glPolygonMode", new int[]{0, 1}), Map.entry("glCreateShader", new int[]{0}), Map.entry("glReadPixels", new int[]{4, 5}),
            Map.entry("glDepthFunc", new int[]{0}), Map.entry("glCullFace", new int[]{0}), Map.entry("glHint", new int[]{0}),
            Map.entry("glStencilFunc", new int[]{0}), Map.entry("glStencilOp", new int[]{0, 1, 2}), Map.entry("glLogicOp", new int[]{0}),
            Map.entry("glVertexAttribPointer", new int[]{2}), Map.entry("glBlitFramebuffer", new int[]{8, 9}),
            Map.entry("glGetProgrami", new int[]{1}), Map.entry("glGetShaderi", new int[]{1}), Map.entry("glActiveTexture", new int[]{0}));

    private GlCoverageScan() {
    }

    /** One mod (a top-level jar; nested jars are folded in). */
    static final class ModResult {
        String file;
        String id;
        String name;
        String version;
        String license;
        List<String> nested = new ArrayList<>();
        int classes;
        int rawGlClasses;
        int callSites;
        /** "Owner.name(desc)" → call sites, for every retargetable GL call in a raw-GL class. */
        Map<String, Integer> functions = new TreeMap<>();
        /** Subset of functions without a metallium-gl implementation. */
        Set<String> missing = new TreeSet<>();
        /** GL method references (e.g. GL11::glEnable), which the retargeter does not rewrite. */
        Set<String> methodReferences = new TreeSet<>();
        /** Other LWJGL GL API calls (GLDebugMessageCallback.create, GL.getFunctionProvider, ...). */
        Set<String> otherLwjgl = new TreeSet<>();
        /** Minecraft GL-backend references the retargeter handles in routed classes (GlStateManager._x, glId()). */
        Set<String> backendHandled = new TreeSet<>();
        /** Handled backend references in classes that are not routed (no raw GL call): they run Minecraft's GL code. */
        Set<String> backendHandledUnrouted = new TreeSet<>();
        /** Minecraft GL-backend references that cannot be translated (class → what it uses). */
        Map<String, Set<String>> backendUnhandled = new TreeMap<>();
        /** "function#param" → constant values passed. */
        Map<String, Set<Integer>> constants = new TreeMap<>();
        /** Classes of the mod that are Mixins into Minecraft's GL backend only (inactive on Metal). */
        int backendOnlyMixins;
        String error;
    }

    public static void main(final String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: GlCoverageScan <out.json> <jar-or-dir>...");
            System.exit(2);
        }
        List<Path> jars = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Path p = Path.of(args[i]);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(jars::add);
                }
            } else {
                jars.add(p);
            }
        }
        List<ModResult> results = new ArrayList<>();
        for (Path jar : jars) {
            ModResult r = new ModResult();
            r.file = jar.getFileName().toString();
            try {
                scanJar(Files.readAllBytes(jar), r, true);
            } catch (IOException | RuntimeException e) {
                r.error = e.toString();
            }
            results.add(r);
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(Path.of(args[0]), gson.toJson(results));
        System.out.println("scanned " + results.size() + " jars -> " + args[0]);
    }

    private static void scanJar(final byte[] jar, final ModResult r, final boolean top) throws IOException {
        List<byte[]> nested = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) {
                    metadata(zip.readAllBytes(), r, top);
                } else if (n.endsWith(".jar")) {
                    nested.add(zip.readAllBytes());
                } else if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    scanClass(zip.readAllBytes(), r);
                }
            }
        }
        for (byte[] inner : nested) {
            String id = nestedId(inner);
            if (id != null && ModScan.excluded(id)) continue; // Fabric API modules, mixinextras, ...
            r.nested.add(id == null ? "?" : id);
            scanJar(inner, r, false);
        }
    }

    private static String nestedId(final byte[] jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("fabric.mod.json")) {
                    JsonObject o = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8).replace('\t', ' ')).getAsJsonObject();
                    return o.has("id") ? o.get("id").getAsString() : null;
                }
            }
        }
        return null;
    }

    private static void metadata(final byte[] json, final ModResult r, final boolean top) {
        if (!top) return;
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8).replace('\t', ' ')).getAsJsonObject();
            r.id = o.has("id") ? o.get("id").getAsString() : null;
            r.name = o.has("name") ? o.get("name").getAsString() : r.id;
            r.version = o.has("version") ? o.get("version").getAsString() : null;
            if (o.has("license")) r.license = o.get("license").isJsonArray() ? o.get("license").getAsJsonArray().toString() : o.get("license").getAsString();
        } catch (RuntimeException e) {
            r.error = "fabric.mod.json: " + e;
        }
    }

    private static void scanClass(final byte[] bytes, final ModResult r) {
        r.classes++;
        GlUsageScanner.ClassUsage usage;
        try {
            usage = GlUsageScanner.scan(bytes);
        } catch (RuntimeException e) {
            return; // unreadable class (e.g. a newer class-file version than ASM knows): Metallium's scan skips it too
        }
        if (usage == null) {
            if (contains(bytes, BACKEND)) r.backendOnlyMixins++; // mentions the backend but GlUsageScanner dismissed it
            return;
        }
        boolean routed = usage.rawGl(); // automatic routing retargets exactly the raw-GL classes
        if (routed) r.rawGlClasses++;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Set<String> unhandled = new TreeSet<>();
        if (node.superName != null && node.superName.startsWith(BACKEND)) unhandled.add("extends " + simple(node.superName));
        if (node.interfaces != null) {
            for (String i : node.interfaces) if (i.startsWith(BACKEND)) unhandled.add("implements " + simple(i));
        }
        for (FieldNode f : node.fields) {
            if (f.desc.contains("L" + BACKEND)) unhandled.add("field " + simple(Type.getType(f.desc).getClassName().replace('.', '/')));
        }
        for (MethodNode m : node.methods) {
            if (m.desc.contains("L" + BACKEND)) unhandled.add("signature " + m.name + m.desc.replaceAll("L" + BACKEND + "(\\w+);", "$1"));
            Frame<SourceValue>[] frames = null;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    String owner = call.owner;
                    if (owner.startsWith(LWJGL_GL)) {
                        if (call.getOpcode() == Opcodes.INVOKESTATIC && GlCallRetargeter.isGlCall(owner, call.name)) {
                            if (!routed) continue;
                            String key = simple(owner) + "." + call.name + call.desc;
                            r.functions.merge(key, 1, Integer::sum);
                            r.callSites++;
                            if (!GlCallRetargeter.isImplemented(call.name, call.desc)) r.missing.add(key);
                            int[] params = ENUM_PARAMS.get(call.name);
                            if (params != null) {
                                if (frames == null) frames = analyze(node.name, m);
                                recordConstants(r, m, frames, call, params);
                            }
                        } else {
                            r.otherLwjgl.add(simple(owner) + "." + call.name);
                        }
                    } else if (owner.startsWith(BACKEND)) {
                        if (owner.equals(STATE_MANAGER) && call.getOpcode() == Opcodes.INVOKESTATIC && call.name.startsWith("_")) {
                            String key = "GlStateManager." + call.name + call.desc;
                            boolean impl = GlCallRetargeter.isStateManagerImplemented(call.name, call.desc);
                            if (routed && impl) r.backendHandled.add(key);
                            else if (!routed) r.backendHandledUnrouted.add(key + (impl ? "" : " (no twin)"));
                            else unhandled.add("GlStateManager." + call.name + " (no MetalGLStateManager twin)");
                        } else if (GL_TEXTURE_CLASSES.contains(owner) && call.name.equals("glId") && prevIsCast(call, owner)) {
                            if (routed) r.backendHandled.add(simple(owner) + ".glId()");
                            else r.backendHandledUnrouted.add(simple(owner) + ".glId()");
                        } else {
                            unhandled.add(simple(owner) + "." + call.name);
                        }
                    } else if (call.desc.contains("L" + BACKEND)) {
                        unhandled.add("passes " + call.desc.replaceAll(".*L" + BACKEND + "(\\w+);.*", "$1") + " to " + simple(owner) + "." + call.name);
                    }
                } else if (insn instanceof TypeInsnNode t && t.desc.startsWith(BACKEND)) {
                    if (!(t.getOpcode() == Opcodes.CHECKCAST && GL_TEXTURE_CLASSES.contains(t.desc) && nextIsGlId(t))) {
                        unhandled.add((t.getOpcode() == Opcodes.NEW ? "new " : t.getOpcode() == Opcodes.CHECKCAST ? "cast " : "instanceof ") + simple(t.desc));
                    }
                } else if (insn instanceof FieldInsnNode f && (f.owner.startsWith(BACKEND) || f.desc.contains("L" + BACKEND))) {
                    unhandled.add("field " + simple(f.owner) + "." + f.name);
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t && t.getInternalName().startsWith(BACKEND)) {
                    unhandled.add("class literal " + simple(t.getInternalName()));
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h && GlCallRetargeter.isGlCall(h.getOwner(), h.getName())) {
                            r.methodReferences.add(simple(h.getOwner()) + "::" + h.getName());
                        } else if (arg instanceof Handle h && h.getOwner().startsWith(BACKEND)) {
                            unhandled.add("method reference " + simple(h.getOwner()) + "::" + h.getName());
                        }
                    }
                }
            }
        }
        if (!unhandled.isEmpty()) r.backendUnhandled.put(node.name.replace('/', '.'), unhandled);
    }

    private static boolean prevIsCast(final MethodInsnNode call, final String owner) {
        AbstractInsnNode p = call.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();
        return p instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST && t.desc.equals(owner);
    }

    private static boolean nextIsGlId(final TypeInsnNode cast) {
        AbstractInsnNode n = cast.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n instanceof MethodInsnNode m && m.owner.equals(cast.desc) && m.name.equals("glId");
    }

    @SuppressWarnings("unchecked")
    private static Frame<SourceValue>[] analyze(final String owner, final MethodNode m) {
        try {
            return new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (AnalyzerException | RuntimeException e) {
            return new Frame[0];
        }
    }

    private static void recordConstants(final ModResult r, final MethodNode m, final Frame<SourceValue>[] frames, final MethodInsnNode call,
                                        final int[] params) {
        int index = m.instructions.indexOf(call);
        if (index >= frames.length || frames[index] == null) return;
        Frame<SourceValue> frame = frames[index];
        Type[] args = Type.getArgumentTypes(call.desc);
        // Stack slots (values, not words) of the arguments: the last args.length values.
        int base = frame.getStackSize() - args.length;
        for (int p : params) {
            if (p >= args.length || args[p].getSort() != Type.INT) continue;
            SourceValue v = frame.getStack(base + p);
            for (AbstractInsnNode src : v.insns) {
                Integer c = constant(src);
                if (c != null) r.constants.computeIfAbsent(call.name + "#" + p, k -> new TreeSet<>()).add(c);
            }
        }
    }

    private static Integer constant(final AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) return i;
        if (insn instanceof IntInsnNode in && (in.getOpcode() == Opcodes.SIPUSH || in.getOpcode() == Opcodes.BIPUSH)) return in.operand;
        if (insn instanceof InsnNode n && n.getOpcode() >= Opcodes.ICONST_M1 && n.getOpcode() <= Opcodes.ICONST_5) return n.getOpcode() - Opcodes.ICONST_0;
        return null;
    }

    private static String simple(final String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static boolean contains(final byte[] haystack, final String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
