/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.screen;

import dev.metallium.MetalliumConfig;
import dev.metallium.MetalliumConfig.ModOverride;
import dev.metallium.compat.CompatStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "Metallium" compatibility status: engine and Metal details, Sodium/Iris/DH/Voxy state, power state, and a per-mod
 * table (native / via metallium-gl / partial / not working). Mods with direct OpenGL use get an override button
 * (Auto → Native → metallium-gl; applies after a restart). Reached from the Sodium settings tab or vanilla's Video
 * Settings.
 */
public final class MetalliumStatusScreen extends Screen {
    private static final int LINE = 11;
    private static final int TOP = 32;
    private final Screen parent;
    private List<CompatStatus.Row> rows = List.of();
    private final List<Button> overrideButtons = new ArrayList<>();
    private final List<Integer> overrideRows = new ArrayList<>();
    private double scroll;
    private boolean changed;

    public MetalliumStatusScreen(final Screen parent) {
        super(Component.literal("Metallium status"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = CompatStatus.rows();
        overrideButtons.clear();
        overrideRows.clear();
        for (int i = 0; i < rows.size(); i++) {
            String modId = rows.get(i).modId();
            if (modId == null) continue;
            Button button = Button.builder(label(modId), b -> {
                        ModOverride next = switch (MetalliumConfig.INSTANCE.override(modId)) {
                            case AUTO -> ModOverride.NATIVE;
                            case NATIVE -> ModOverride.METALLIUM_GL;
                            case METALLIUM_GL -> ModOverride.AUTO;
                        };
                        MetalliumConfig.INSTANCE.setOverride(modId, next);
                        MetalliumConfig.INSTANCE.save();
                        b.setMessage(label(modId));
                        changed = true;
                    })
                    .tooltip(Tooltip.create(Component.literal("Auto: native if the mod only uses Minecraft's graphics layer, "
                            + "else through metallium-gl.\nNative: never translate its OpenGL calls.\nmetallium-gl: translate all of its classes."
                            + "\nApplies after a restart.")))
                    .size(100, LINE + 1).build();
            overrideButtons.add(addRenderableWidget(button));
            overrideRows.add(i);
        }
        int bottom = this.height - 26;
        addRenderableWidget(Button.builder(Component.literal("Copy report"), b -> this.minecraft.keyboardHandler.setClipboard(CompatStatus.text()))
                .bounds(this.width / 2 - 154, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> rebuildWidgets())
                .bounds(this.width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 + 54, bottom, 100, 20).build());
        layoutButtons();
    }

    private static Component label(final String modId) {
        return Component.literal(switch (MetalliumConfig.INSTANCE.override(modId)) {
            case AUTO -> "Auto";
            case NATIVE -> "Native";
            case METALLIUM_GL -> "metallium-gl";
        });
    }

    private int viewBottom() {
        return this.height - 32;
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * LINE - (viewBottom() - TOP));
    }

    private void layoutButtons() {
        for (int k = 0; k < overrideButtons.size(); k++) {
            Button button = overrideButtons.get(k);
            int y = TOP + overrideRows.get(k) * LINE - (int) scroll - 2;
            button.setX(this.width - 110);
            button.setY(y);
            button.visible = y >= TOP - 2 && y + LINE <= viewBottom();
        }
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double horizontal, final double vertical) {
        scroll = Math.clamp(scroll - vertical * LINE * 3, 0, maxScroll());
        layoutButtons();
        return true;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        graphics.enableScissor(0, TOP - 2, this.width, viewBottom());
        int y = TOP - (int) scroll;
        int textRight = this.width - 116;
        for (CompatStatus.Row row : rows) {
            if (y + LINE >= TOP - 2 && y <= viewBottom()) {
                int color = switch (row.tone()) {
                    case HEADER -> 0xFFFFD060;
                    case OK -> 0xFF80E080;
                    case WARN -> 0xFFFFB040;
                    case BAD -> 0xFFFF6060;
                    case INFO -> 0xFFC8C8C8;
                };
                String text = row.text();
                int max = row.modId() != null ? textRight - 12 : this.width - 24;
                if (this.font.width(text) > max) {
                    text = this.font.plainSubstrByWidth(text, max - this.font.width("…")) + "…";
                }
                graphics.text(this.font, row.tone() == CompatStatus.Tone.HEADER ? "— " + text + " —" : text, 12, y, color);
            }
            y += LINE;
        }
        graphics.disableScissor();
        if (changed) {
            graphics.centeredText(this.font, "Override changed: restart the game to apply", this.width / 2, viewBottom() + 1, 0xFFFFB040);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
