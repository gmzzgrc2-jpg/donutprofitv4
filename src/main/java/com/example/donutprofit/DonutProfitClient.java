package com.example.donutprofit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class DonutProfitClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Config.load();
        Tracker.load();

        ClientTickEvents.END_CLIENT_TICK.register(Tracker::onTick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> Hud.render(ctx));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Tracker.save());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { Tracker.save(); Config.save(); });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = literal("dprofit")
                .then(literal("config").executes(c -> { Tracker.openConfigRequested = true; return 1; }))
                .then(literal("hud").executes(c -> {
                    Config.get().enabled = !Config.get().enabled; Config.save();
                    say(c.getSource(), "HUD " + (Config.get().enabled ? "activado" : "desactivado")); return 1; }))
                .then(literal("session")
                    .then(literal("start").executes(c -> { say(c.getSource(), Tracker.startSession()); return 1; }))
                    .then(literal("stop").executes(c -> { say(c.getSource(), Tracker.stopSession()); return 1; }))
                    .executes(c -> {
                        say(c.getSource(), Tracker.s.sessionActive ? Tracker.stopSession() : Tracker.startSession()); return 1; }))
                .then(literal("reset").executes(c -> { Tracker.resetToday(); say(c.getSource(), "Día reiniciado."); return 1; }))
                .then(literal("history").executes(c -> {
                    List<Map.Entry<String, Stats.Day>> list = new ArrayList<>(Tracker.s.history.entrySet());
                    if (list.isEmpty()) say(c.getSource(), "Sin historial todavía.");
                    for (int i = Math.max(0, list.size() - 7); i < list.size(); i++) {
                        var e = list.get(i);
                        say(c.getSource(), e.getKey() + "  día " + Tracker.fmt(e.getValue().change, true)
                            + "  gambling " + Tracker.fmt(e.getValue().gambling, true)
                            + "  (" + e.getValue().wins + "W/" + e.getValue().losses + "L)");
                    }
                    return 1; }))
                .then(literal("debug").executes(c -> {
                    say(c.getSource(), "Scoreboard:");
                    for (String l : Tracker.sidebarLines(MinecraftClient.getInstance())) say(c.getSource(), " > " + l + "   [" + Tracker.codepoints(l) + "]");
                    say(c.getSource(), "Balance detectado: " + Tracker.balance + " | inicio del día: " + Tracker.s.dayStart);
                    return 1; }))
                .executes(c -> {
                    var s = Tracker.s;
                    say(c.getSource(), "Hoy " + Tracker.fmt(Tracker.todayChange(), true) + " | Gambling " + Tracker.fmt(s.gambling, true)
                        + " | " + s.wins + "W/" + s.losses + "L | mejor +" + Tracker.fmt(s.bestWin, false)
                        + " | peor " + Tracker.fmt(s.worstLoss, false));
                    return 1; });
            var node = dispatcher.register(root);
            dispatcher.register(literal("gamble").redirect(node));
        });
    }

    private static void say(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal("[DonutProfit] ").formatted(Formatting.LIGHT_PURPLE)
            .append(Text.literal(msg).formatted(Formatting.GRAY)));
    }
}
