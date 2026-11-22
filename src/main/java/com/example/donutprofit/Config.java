package com.example.donutprofit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Ajustes del usuario (config/donutprofit.json). */
public final class Config {
    public enum Anchor {
        TOP_LEFT("Arriba izq."), TOP_RIGHT("Arriba der."),
        BOTTOM_LEFT("Abajo izq."), BOTTOM_RIGHT("Abajo der.");

        public final String label;
        Anchor(String label) { this.label = label; }
        public Anchor next() { Anchor[] v = values(); return v[(ordinal() + 1) % v.length]; }
        public boolean right() { return this == TOP_RIGHT || this == BOTTOM_RIGHT; }
        public boolean bottom() { return this == BOTTOM_LEFT || this == BOTTOM_RIGHT; }
    }

    public static final int[] ACCENTS = {0xFFFF7AC6, 0xFF4FD1FF, 0xFFFFC857, 0xFF6EE7A0, 0xFFB794F6, 0xFFFFFFFF};
    public static final String[] ACCENT_NAMES = {"Rosa", "Cian", "Dorado", "Verde", "Violeta", "Blanco"};

    public boolean enabled = true;
    public boolean animations = true;
    public Anchor anchor = Anchor.TOP_LEFT;
    public int offsetX = 6;
    public int offsetY = 6;
    public float scale = 1.0f;
    public float opacity = 0.72f;
    public int accent = 0;
    public int radius = 5;
    public boolean shadow = true;
    public boolean showBalance = true;
    public boolean showToday = true;
    public boolean showGambling = true;
    public boolean showWinLoss = true;
    public boolean showSession = true;
    public boolean showGraph = true;
    public boolean toasts = true;
    public boolean compact = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("donutprofit.json");
    private static Config instance = new Config();

    public static Config get() { return instance; }

    public int accentColor() { return ACCENTS[Math.floorMod(accent, ACCENTS.length)]; }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Config c = GSON.fromJson(Files.readString(FILE), Config.class);
                if (c != null) { c.sanitize(); instance = c; }
            }
        } catch (Exception ignored) { }
        save();
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(instance)); } catch (Exception ignored) { }
    }

    private void sanitize() {
        if (anchor == null) anchor = Anchor.TOP_LEFT;
        scale = Math.max(0.5f, Math.min(2.5f, scale));
        opacity = Math.max(0f, Math.min(1f, opacity));
        offsetX = Math.max(0, Math.min(400, offsetX));
        offsetY = Math.max(0, Math.min(400, offsetY));
        radius = Math.max(0, Math.min(10, radius));
        accent = Math.floorMod(accent, ACCENTS.length);
    }
}
