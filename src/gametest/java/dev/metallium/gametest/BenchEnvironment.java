/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gametest;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Benchmark environment (owner rule, 2026-10-19): the game window must be frontmost, focused and not occluded for the
 * whole measurement, the Mac must not sleep or dim, and the report states display, resolution, refresh rate and power
 * source. Rows measured while the window was unfocused or occluded (macOS throttles or withholds drawables from such
 * windows), or with no presents at all, are marked INVALID.
 */
final class BenchEnvironment {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", java.lang.foreign.Arena.global());
    private static final MethodHandle GET_CLASS = downcall("objc_getClass", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEL = downcall("sel_registerName", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MemorySegment MSG_SEND = OBJC.findOrThrow("objc_msgSend");
    private static final MethodHandle SEND_PTR = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_LONG = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_VOID_BOOL = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
    private static final MethodHandle SEND_VOID_PTR = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** NSWindowOcclusionStateVisible. */
    private static final long OCCLUSION_VISIBLE = 1L << 1;

    private BenchEnvironment() {
    }

    private static MethodHandle downcall(final String name, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(OBJC.findOrThrow(name), descriptor);
    }

    private static MemorySegment sel(final String name) throws Throwable {
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            return (MemorySegment) SEL.invokeExact(arena.allocateFrom(name));
        }
    }

    private static MemorySegment nsApp() throws Throwable {
        MemorySegment cls;
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            cls = (MemorySegment) GET_CLASS.invokeExact(arena.allocateFrom("NSApplication"));
        }
        return (MemorySegment) SEND_PTR.invokeExact(cls, sel("sharedApplication"));
    }

    private static MemorySegment nsWindow(final long sdlWindow) {
        int props = SDLVideo.SDL_GetWindowProperties(sdlWindow);
        long pointer = props == 0 ? 0L : SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
        return MemorySegment.ofAddress(pointer);
    }

    /** Render (main) thread: activates the app and raises and focuses the game window. Returns what was done. */
    static String activate(final long sdlWindow) {
        StringBuilder result = new StringBuilder();
        result.append("raise=").append(SDLVideo.SDL_RaiseWindow(sdlWindow));
        try {
            SEND_VOID_BOOL.invokeExact(nsApp(), sel("activateIgnoringOtherApps:"), true);
            MemorySegment window = nsWindow(sdlWindow);
            if (window.address() != 0L) {
                SEND_VOID_PTR.invokeExact(window, sel("makeKeyAndOrderFront:"), MemorySegment.NULL);
                result.append(", NSWindow makeKeyAndOrderFront");
            }
            result.append(", NSApp activated");
        } catch (Throwable t) {
            result.append(", activation failed: ").append(t);
        }
        return result.toString();
    }

    /**
     * Fallback when {@link #activate} did not get focus (macOS 14+ cooperative activation ignores
     * activateIgnoringOtherApps while another app is in use): System Events, which may need the Automation permission
     * for the calling terminal. Any thread; returns osascript's exit code and output.
     */
    static String activateViaSystemEvents() {
        try {
            Process process = new ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to set frontmost of (first process whose unix id is "
                            + ProcessHandle.current().pid() + ") to true").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean done = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return "osascript exit=" + (done ? process.exitValue() : "timeout") + (out.isEmpty() ? "" : " " + out);
        } catch (Exception e) {
            return "osascript failed: " + e;
        }
    }

    /** Render thread: true if focused (SDL input focus). */
    static boolean focused(final long sdlWindow) {
        return (SDLVideo.SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0;
    }

    /** Render thread: true if the NSWindow's occlusion state is not "visible" (or SDL reports it occluded/hidden). */
    static boolean occluded(final long sdlWindow) {
        long flags = SDLVideo.SDL_GetWindowFlags(sdlWindow);
        if ((flags & (SDLVideo.SDL_WINDOW_OCCLUDED | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_MINIMIZED)) != 0) {
            return true;
        }
        try {
            MemorySegment window = nsWindow(sdlWindow);
            if (window.address() == 0L) {
                return false;
            }
            long state = (long) SEND_LONG.invokeExact(window, sel("occlusionState"));
            return (state & OCCLUSION_VISIBLE) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** "AC Power" / "Battery Power" (pmset), or "unknown". */
    static String powerSource() {
        try {
            Process process = new ProcessBuilder("pmset", "-g", "ps").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String first = reader.readLine();
                process.waitFor();
                if (first != null && first.contains("'")) {
                    return first.substring(first.indexOf('\'') + 1, first.lastIndexOf('\''));
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    /** Keeps the display and the system awake while this JVM runs (caffeinate -d -i -w pid); null if unavailable. */
    static Process caffeinate() {
        try {
            return new ProcessBuilder("caffeinate", "-d", "-i", "-w", Long.toString(ProcessHandle.current().pid())).start();
        } catch (Exception e) {
            return null;
        }
    }

    static String validity(final boolean everUnfocused, final boolean everOccluded, final double presentedPerSecond, final boolean metal) {
        StringBuilder reasons = new StringBuilder();
        if (everUnfocused) reasons.append("unfocused,");
        if (everOccluded) reasons.append("occluded,");
        if (metal && presentedPerSecond <= 0.0) reasons.append("no presents,");
        return reasons.isEmpty() ? "VALID" : "INVALID(" + reasons.substring(0, reasons.length() - 1) + ")";
    }

    static String fmt(final String format, final Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
