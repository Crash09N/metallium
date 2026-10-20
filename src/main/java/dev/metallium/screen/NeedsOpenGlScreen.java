/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.screen;

import dev.metallium.MetalliumConfig.OpenGlChoice;
import dev.metallium.compat.CrashGuard;
import dev.metallium.compat.GlRouting;
import dev.metallium.compat.OpenGlRequirements;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown before the title screen when a mod needs Minecraft's OpenGL backend ({@link OpenGlRequirements}) and the
 * player has not answered yet. The session already runs on OpenGL, so nothing crashes; the answer is remembered in
 * {@code config/metallium.json} ({@code openGlChoices}). Disabling renames the mod's jar and only happens on a click.
 */
public final class NeedsOpenGlScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB0B0B0;
    private static final int GOLD = 0xFFFFC040;
    private final Screen next;
    private final List<OpenGlRequirements.Requirement> mods;
    private final List<String> message = new ArrayList<>();
    private final List<Line> wrapped = new ArrayList<>();
    private String result;

    private record Line(FormattedCharSequence text, int color) {
    }

    public NeedsOpenGlScreen(final Screen next, final List<OpenGlRequirements.Requirement> mods) {
        super(Component.literal(names(mods) + (mods.size() == 1 ? " needs" : " need") + " OpenGL"));
        this.next = next;
        this.mods = List.copyOf(mods);
        for (OpenGlRequirements.Requirement mod : mods) {
            message.add(mod.name() + " " + mod.version() + ": " + mod.reason()
                    + (mod.listed() ? "" : " (found by Metallium's mod scan)"));
        }
        message.add("On Metal this would abort the game during start-up, so this session runs on OpenGL.");
        message.add("Nothing has been changed. What should Metallium do?");
    }

    private static String names(final List<OpenGlRequirements.Requirement> mods) {
        return String.join(", ", mods.stream().map(OpenGlRequirements.Requirement::name).toList());
    }

    @Override
    protected void init() {
        int w = 300;
        int x = this.width / 2 - w / 2;
        wrapped.clear();
        for (String line : message) {
            int color = line.startsWith("On Metal") || line.startsWith("Nothing") ? GRAY : WHITE;
            for (FormattedCharSequence part : this.font.split(Component.literal(line), Math.max(200, this.width - 40))) {
                wrapped.add(new Line(part, color));
            }
        }
        int y = Math.max(this.height / 2 - 30, 40 + wrapped.size() * 11 + 10);
        if (result != null) {
            addRenderableWidget(Button.builder(Component.literal("Quit game"), b -> this.minecraft.stop()).bounds(x, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Continue on OpenGL"), b -> onClose()).bounds(x, y + 24, w, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("Run on OpenGL (remember)"), b -> {
                    OpenGlRequirements.choose(mods, OpenGlChoice.OPENGL);
                    onClose();
                })
                .tooltip(Tooltip.create(Component.literal("Continue now. Future starts use OpenGL while " + names(mods)
                        + " is installed; other starts keep using Metal.")))
                .bounds(x, y, w, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Run this session on OpenGL (ask again)"), b -> onClose())
                .bounds(x, y, w, 20).build());
        y += 24;
        for (OpenGlRequirements.Requirement mod : mods) {
            String jar = GlRouting.jarOf(mod.id());
            Button disable = Button.builder(Component.literal("Disable " + this.font.plainSubstrByWidth(mod.name(), w - 60) + " (restart)"),
                    b -> disable(mod, jar)).bounds(x, y, w, 20).build();
            if (jar == null) {
                disable.active = false;
                disable.setTooltip(Tooltip.create(Component.literal("Not a plain jar in the mods folder (e.g. a development build)")));
            } else {
                disable.setTooltip(Tooltip.create(Component.literal("Renames " + jar + " to .jar.disabled; the next start uses Metal."
                        + " Rename it back to re-enable.")));
            }
            addRenderableWidget(disable);
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.literal("Try Metal anyway (experimental)"), b -> {
                    OpenGlRequirements.choose(mods, OpenGlChoice.METAL);
                    result = "The next start uses Metal with " + names(mods) + ". If it closes during start-up, Metallium asks again.";
                    rebuildWidgets();
                })
                .tooltip(Tooltip.create(Component.literal("Remembers the choice; Metal needs a restart.")))
                .bounds(x, y, w, 20).build());
    }

    private void disable(final OpenGlRequirements.Requirement mod, final String jar) {
        try {
            String renamed = CrashGuard.disableJar(new CrashGuard.RoutedMod(mod.id(), mod.name(), List.of(), jar));
            result = "Disabled " + mod.name() + ": renamed to " + renamed + ". Restart the game.";
        } catch (IOException e) {
            result = "Could not disable " + mod.name() + ": " + e.getMessage();
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, Component.literal("Metallium: " + this.title.getString()), this.width / 2, 20, GOLD);
        int y = 40;
        for (Line line : wrapped) {
            graphics.centeredText(this.font, line.text(), this.width / 2, y, line.color());
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
