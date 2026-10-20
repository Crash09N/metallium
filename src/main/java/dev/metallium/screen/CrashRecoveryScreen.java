/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.screen;

import dev.metallium.EngineSync;
import dev.metallium.MetalliumConfig;
import dev.metallium.compat.CrashGuard;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown before the title screen when the last session crashed in Metallium's Metal backend, metallium-gl or a mod whose
 * GL calls ran through metallium-gl. This session already runs on OpenGL (safe mode); the user chooses what happens
 * next. Disabling a mod renames its jar (reversible) and only happens on an explicit click.
 */
public final class CrashRecoveryScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB0B0B0;
    private static final int GOLD = 0xFFFFC040;
    private final Screen next;
    private final CrashGuard.Recovery recovery;
    private final List<String> message = new ArrayList<>();
    private String result;

    public CrashRecoveryScreen(final Screen next, final CrashGuard.Recovery recovery) {
        super(Component.literal("Metallium: the last session crashed"));
        this.next = next;
        this.recovery = recovery;
        if (recovery.startup()) {
            message.add("The game closed during start-up on Metal without a crash report,");
            message.add("typically when a mod calls OpenGL where Metal has no OpenGL context.");
            if (!recovery.suspects().isEmpty()) {
                message.add("Likely: " + String.join(", ", recovery.suspects().stream().map(CrashGuard.RoutedMod::name).toList())
                        + " (uses Minecraft's OpenGL backend).");
            }
        } else if (!recovery.suspects().isEmpty()) {
            for (CrashGuard.RoutedMod mod : recovery.suspects()) {
                message.add(mod.name() + " (" + mod.id() + ") may not work with Metallium:");
            }
            message.add("its OpenGL calls ran through metallium-gl and it appears in the crash.");
        } else if (recovery.metalliumGl()) {
            message.add("The crash happened in metallium-gl (Metallium's OpenGL translation layer).");
        } else {
            message.add("The crash happened in Metallium's Metal renderer.");
        }
        message.add("Evidence: " + recovery.evidence());
        message.add("To be safe, this session runs on OpenGL. Nothing has been changed or deleted.");
    }

    @Override
    protected void init() {
        int w = 260;
        int x = this.width / 2 - w / 2;
        int y = Math.max(this.height / 2 - 20, 40 + message.size() * 11 + 10);
        if (result != null) {
            addRenderableWidget(Button.builder(Component.literal("Quit game"), b -> this.minecraft.stop()).bounds(x, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Continue on OpenGL"), b -> onClose()).bounds(x, y + 24, w, 20).build());
            return;
        }
        for (CrashGuard.RoutedMod mod : recovery.suspects()) {
            Button disable = Button.builder(Component.literal("Disable " + this.font.plainSubstrByWidth(mod.name(), w - 60)), b -> disable(mod))
                    .bounds(x, y, w, 20).build();
            if (mod.jar() == null) {
                disable.active = false;
                disable.setTooltip(Tooltip.create(Component.literal("Not a plain jar in the mods folder (e.g. a development build)")));
            } else {
                disable.setTooltip(Tooltip.create(Component.literal("Renames " + mod.jar() + " to .jar.disabled, then asks you to restart."
                        + " Rename it back to re-enable.")));
            }
            addRenderableWidget(disable);
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.literal("Use OpenGL for this session"), b -> onClose())
                .tooltip(Tooltip.create(Component.literal("Continue. The next start tries Metal again.")))
                .bounds(x, y, w, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Always use OpenGL"), b -> {
                    EngineSync.apply(MetalliumConfig.GraphicsEngine.OPENGL, this.minecraft.options);
                    onClose();
                })
                .tooltip(Tooltip.create(Component.literal("Sets Graphics Engine to OpenGL (change it back in Video Settings → Metallium).")))
                .bounds(x, y, w, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Try Metal again (quit game)"), b -> this.minecraft.stop())
                .tooltip(Tooltip.create(Component.literal("Quits; start the game again to retry with Metal.")))
                .bounds(x, y, w, 20).build());
    }

    private void disable(final CrashGuard.RoutedMod mod) {
        try {
            result = "Disabled " + mod.name() + ": renamed to " + CrashGuard.disableJar(mod) + ". Restart the game.";
        } catch (IOException e) {
            result = "Could not disable " + mod.name() + ": " + e.getMessage();
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, GOLD);
        int y = 40;
        for (String line : message) {
            graphics.centeredText(this.font, line, this.width / 2, y, line.startsWith("Evidence") ? GRAY : WHITE);
            y += 11;
        }
        if (result != null) {
            graphics.centeredText(this.font, result, this.width / 2, y + 4, GOLD);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(next);
    }
}
