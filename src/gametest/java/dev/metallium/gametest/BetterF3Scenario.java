package dev.metallium.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * BetterF3 scenario ({@code ./gradlew :runClientGameTest -PwithBetterF3=/abs/BetterF3.jar [-PwithIris]}): BetterF3 replaces
 * the vanilla F3 text with its own modules, so Metallium's lines must reach its HUD. Opens F3 in a world, reads the lines
 * BetterF3 renders (its module line lists, via reflection) and fails when the Metallium lines are missing. The screenshot
 * ({@code betterf3-f3}) still has to be looked at.
 */
final class BetterF3Scenario {
    private final Consumer<String> line;
    private final List<String> failures;

    BetterF3Scenario(final Consumer<String> line, final List<String> failures) {
        this.line = line;
        this.failures = failures;
    }

    void run(final ClientGameTestContext context, final String backend) {
        line.accept("betterf3: backend " + backend);
        closeForeignScreens(context);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            closeForeignScreens(context);
            context.waitTicks(100);
            context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
            context.waitTicks(60); // BetterF3 slides its text in
            context.takeScreenshot("betterf3-f3");
            List<String> rendered = context.computeOnClient(client -> betterF3Lines());
            for (String l : rendered) line.accept("betterf3 F3: " + l);
            List<String> expected = new ArrayList<>(List.of("Metallium"));
            if (backend.startsWith("Metal")) expected.add("Metal active");
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("metallium-iris")) expected.add("Iris");
            String all = String.join("\n", rendered);
            for (String needle : expected) {
                if (!all.contains(needle)) failures.add("betterf3: F3 lines lack \"" + needle + "\"");
            }
            for (String status : dev.metallium.api.MetalliumRuntime.statusLines()) {
                if (!all.contains(status)) failures.add("betterf3: status line missing from BetterF3 F3: " + status);
            }
            context.runOnClient(client -> client.debugEntries.setOverlayVisible(false));
        }
    }

    /** All text BetterF3 would draw right now (left and right module lists), via reflection; render thread. */
    @SuppressWarnings("unchecked")
    private static List<String> betterF3Lines() {
        List<String> out = new ArrayList<>();
        try {
            Class<?> base = Class.forName("me.cominixo.betterf3.modules.BaseModule");
            for (String field : new String[] {"modules", "modulesRight"}) {
                out.add("-- " + field);
                for (Object module : (List<Object>) base.getField(field).get(null)) {
                    List<net.minecraft.network.chat.Component> lines = (List<net.minecraft.network.chat.Component>)
                            base.getMethod("cachedLinesFormatted", boolean.class).invoke(module, false);
                    for (net.minecraft.network.chat.Component c : lines) out.add(c.getString());
                }
            }
        } catch (ReflectiveOperationException e) {
            out.add("unavailable: " + e);
        }
        return out;
    }

    private void closeForeignScreens(final ClientGameTestContext context) {
        for (int i = 0; i < 5; i++) {
            String name = context.computeOnClient(client -> {
                Screen s = client.gui.screen();
                if (s == null || s instanceof TitleScreen) return null;
                client.gui.setScreen(null);
                return s.getClass().getName();
            });
            if (name == null) return;
            line.accept("betterf3: closed foreign screen " + name);
            context.waitTicks(5);
        }
    }
}
