/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.power;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * macOS power state via FFM: {@code NSProcessInfo.processInfo.isLowPowerModeEnabled} / {@code thermalState} (Foundation,
 * through {@code objc_msgSend}) and the providing power source from IOKit ({@code IOPSCopyPowerSourcesInfo} +
 * {@code IOPSGetProvidingPowerSourceType}: "AC Power", "Battery Power", "Off Line").
 */
final class MacPowerSource {
    private static final int K_CF_STRING_ENCODING_UTF8 = 0x08000100;
    private final MethodHandle msgSendLong;
    private final MethodHandle msgSendBool;
    private final MethodHandle copyInfo;
    private final MethodHandle providingType;
    private final MethodHandle cfStringGetCString;
    private final MethodHandle cfRelease;
    private final long processInfoClass;
    private final long selProcessInfo;
    private final long selLowPower;
    private final long selThermal;

    MacPowerSource() throws Throwable {
        Linker linker = Linker.nativeLinker();
        Arena arena = Arena.global();
        SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
        // Foundation must be loaded for NSProcessInfo to be registered with the runtime.
        SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena);
        SymbolLookup iokit = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit", arena);
        SymbolLookup cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena);
        MethodHandle getClass = linker.downcallHandle(objc.findOrThrow("objc_getClass"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        MethodHandle selRegister = linker.downcallHandle(objc.findOrThrow("sel_registerName"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        MemorySegment msgSend = objc.findOrThrow("objc_msgSend");
        msgSendLong = linker.downcallHandle(msgSend, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        msgSendBool = linker.downcallHandle(msgSend, FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        copyInfo = linker.downcallHandle(iokit.findOrThrow("IOPSCopyPowerSourcesInfo"), FunctionDescriptor.of(ValueLayout.ADDRESS));
        providingType = linker.downcallHandle(iokit.findOrThrow("IOPSGetProvidingPowerSourceType"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        cfStringGetCString = linker.downcallHandle(cf.findOrThrow("CFStringGetCString"),
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        cfRelease = linker.downcallHandle(cf.findOrThrow("CFRelease"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        try (Arena temp = Arena.ofConfined()) {
            processInfoClass = (long) getClass.invokeExact(temp.allocateFrom("NSProcessInfo"));
            selProcessInfo = (long) selRegister.invokeExact(temp.allocateFrom("processInfo"));
            selLowPower = (long) selRegister.invokeExact(temp.allocateFrom("isLowPowerModeEnabled"));
            selThermal = (long) selRegister.invokeExact(temp.allocateFrom("thermalState"));
        }
        if (processInfoClass == 0) {
            throw new IllegalStateException("NSProcessInfo not found");
        }
    }

    PowerState read() throws Throwable {
        long info = (long) msgSendLong.invokeExact(processInfoClass, selProcessInfo);
        boolean lowPower = (boolean) msgSendBool.invokeExact(info, selLowPower);
        long thermal = (long) msgSendLong.invokeExact(info, selThermal);
        String source = "unknown";
        MemorySegment blob = (MemorySegment) copyInfo.invokeExact();
        if (blob.address() != 0) {
            try {
                MemorySegment type = (MemorySegment) providingType.invokeExact(blob); // not owned (Get rule)
                if (type.address() != 0) {
                    try (Arena temp = Arena.ofConfined()) {
                        MemorySegment buffer = temp.allocate(64);
                        if ((boolean) cfStringGetCString.invokeExact(type, buffer, 64L, K_CF_STRING_ENCODING_UTF8)) {
                            source = buffer.getString(0);
                        }
                    }
                }
            } finally {
                cfRelease.invokeExact(blob);
            }
        }
        return new PowerState(true, lowPower, "Battery Power".equals(source), source, (int) thermal);
    }
}
