/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.screen;

import de.nprmedia.nativium.compat.voxy.VoxyNatives;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown once per session before the title screen on a Mac where Voxy's bundled storage libraries have no macOS native
 * (or the {@code nativium-voxy-natives} addon is for other library versions). When natives are missing Voxy is already
 * disabled for this session ({@code VoxyClientMixin}), so joining a world does not crash; this screen says why and what
 * to install.
 */
public final class VoxyNativesScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GOLD = 0xFFFFC040;
    private static boolean shown;
    private final Screen next;
    private final List<String> message = new ArrayList<>();
    private final List<FormattedCharSequence> wrapped = new ArrayList<>();

    /** True until acknowledged once per session, when there is something to tell (macOS arm64 only). */
    public static boolean shouldShow() {
        return !shown && !VoxyNatives.problems().isEmpty();
    }

    public VoxyNativesScreen(final Screen next) {
        super(Component.literal(VoxyNatives.anyMissing() ? "Voxy needs macOS native libraries" : "Voxy's macOS native libraries do not match"));
        this.next = next;
        for (VoxyNatives.Problem problem : VoxyNatives.problems()) {
            message.add(problem.describe() + ".");
        }
        message.add(VoxyNatives.blocksVoxy()
                ? "Without them Voxy would crash the game when you join a world, so Nativium has disabled Voxy for this session."
                : VoxyNatives.anyMissing() ? "Mods that use these libraries may fail on a Mac."
                : "Voxy may fail or crash when it opens its world storage.");
        message.add(VoxyNatives.advice());
    }

    @Override
    protected void init() {
        wrapped.clear();
        for (String line : message) {
            wrapped.addAll(this.font.split(Component.literal(line), Math.max(200, this.width - 40)));
            wrapped.add(FormattedCharSequence.EMPTY);
        }
        int w = 200;
        int y = Math.max(this.height / 2, 40 + wrapped.size() * 11 + 10);
        addRenderableWidget(Button.builder(Component.literal("Continue"), b -> onClose()).bounds(this.width / 2 - w / 2, y, w, 20).build());
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, Component.literal("Nativium: " + this.title.getString()), this.width / 2, 20, GOLD);
        int y = 40;
        for (FormattedCharSequence line : wrapped) {
            graphics.centeredText(this.font, line, this.width / 2, y, WHITE);
            y += 11;
        }
    }

    @Override
    public void onClose() {
        shown = true; // only once acknowledged: something replacing it with the title screen must not swallow it
        this.minecraft.gui.setScreen(next);
    }
}
