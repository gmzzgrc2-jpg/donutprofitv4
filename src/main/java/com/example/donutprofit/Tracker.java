package com.example.donutprofit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lee el balance del scoreboard y calcula profit diario / sesiones de gambling. */
public final class Tracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("donutprofit-stats.json");
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d.,]*)\\s*([kmbtKMBT])?(?![a-zA-Z])");
    private static final int MAX_SAMPLES = 60;

    public static Stats s = new Stats();
    /** Balance actual leído del scoreboard, -1 si desconocido. */
    public static double balance = -1;
    public static boolean openConfigRequested = false;

    /** Se incrementa con cada cambio de balance (para toasts). */
    public static long deltaSeq = 0;
    public static double lastDelta = 0;

    private static final List<Double> sampGamb = new ArrayList<>();
    private static final List<Double> sampToday = new ArrayList<>();
    private static int tick;

    // ---------- persistencia ----------

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Stats loaded = GSON.fromJson(Files.readString(FILE), Stats.class);
                if (loaded != null) s = loaded;
            }
        } catch (Exception ignored) { }
        if (s.history == null) s.history = new LinkedHashMap<>();
        rollover();
        save();
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(s)); } catch (Exception ignored) { }
    }

    // ---------- tick ----------

    public static void onTick(MinecraftClient mc) {
        if (openConfigRequested && mc.currentScreen == null) {
            openConfigRequested = false;
            mc.setScreen(new ConfigScreen(null));
        }
        if (mc.world == null || mc.player == null) { balance = -1; return; }
        if (++tick % 5 != 0) return;
        double v = readBalance(mc);
        if (v >= 0) applyBalance(v);
        if (tick % 200 == 0 && balance >= 0) sampleNow();
        rollover();
    }

    private static void applyBalance(double v) {
        boolean changed = false;
        if (s.dayStart < 0) { s.dayStart = v; changed = true; }
        if (balance >= 0 && v != balance) {
            double delta = v - balance;
            if (s.sessionActive) {
                s.sessionProfit += delta;
                s.gambling += delta;
                if (delta > 0) {
                    s.wins++; s.sessionWins++; s.bestWin = Math.max(s.bestWin, delta);
                } else {
                    s.losses++; s.sessionLosses++; s.worstLoss = Math.min(s.worstLoss, delta);
                }
            }
            lastDelta = delta;
            deltaSeq++;
            balance = v;
            s.lastBalance = v;
            sampleNow();
            save();
            return;
        }
        balance = v;
        s.lastBalance = v;
        if (changed) save();
    }

    private static void rollover() {
        String today = LocalDate.now().toString();
        if (today.equals(s.day)) return;
        if (!s.day.isEmpty()) {
            Stats.Day rec = new Stats.Day();
            rec.change = (s.lastBalance >= 0 && s.dayStart >= 0) ? s.lastBalance - s.dayStart : 0;
            rec.gambling = s.gambling; rec.wins = s.wins; rec.losses = s.losses;
            s.history.put(s.day, rec);
            while (s.history.size() > 60) {
                Iterator<String> it = s.history.keySet().iterator();
                it.next(); it.remove();
            }
        }
        s.day = today;
        s.dayStart = balance >= 0 ? balance : -1;
        s.gambling = 0; s.wins = 0; s.losses = 0; s.bestWin = 0; s.worstLoss = 0;
        sampGamb.clear(); sampToday.clear();
        save();
    }

    // ---------- muestras para el gráfico ----------

    private static void sampleNow() {
        sampGamb.add(s.gambling);
        sampToday.add(todayChange());
        while (sampGamb.size() > MAX_SAMPLES) sampGamb.remove(0);
        while (sampToday.size() > MAX_SAMPLES) sampToday.remove(0);
    }

    /** Historial reciente (más el valor actual al final). */
    public static double[] graph(boolean gambling) {
        List<Double> src = gambling ? sampGamb : sampToday;
        double[] out = new double[src.size() + 1];
        for (int i = 0; i < src.size(); i++) out[i] = src.get(i);
        out[out.length - 1] = gambling ? s.gambling : todayChange();
        return out;
    }

    // ---------- acciones ----------

    public static String startSession() {
        if (s.sessionActive) return "Ya hay una sesión activa.";
        if (balance < 0) return "Aún no detecto tu balance (mira /dprofit debug).";
        s.sessionActive = true; s.sessionProfit = 0; s.sessionWins = 0; s.sessionLosses = 0;
        s.sessionStartMs = System.currentTimeMillis();
        return "Sesión de gambling iniciada.";
    }

    public static String stopSession() {
        if (!s.sessionActive) return "No hay sesión activa.";
        s.sessionActive = false;
        save();
        return "Sesión terminada: " + fmt(s.sessionProfit, true) + "  (" + s.sessionWins + "W / " + s.sessionLosses + "L, "
                + time(System.currentTimeMillis() - s.sessionStartMs) + ")";
    }

    public static void resetToday() {
        s.gambling = 0; s.wins = 0; s.losses = 0; s.bestWin = 0; s.worstLoss = 0;
        s.dayStart = balance >= 0 ? balance : -1;
        s.sessionProfit = 0; s.sessionWins = 0; s.sessionLosses = 0;
        sampGamb.clear(); sampToday.clear();
        save();
    }

    public static double todayChange() {
        return (balance >= 0 && s.dayStart >= 0) ? balance - s.dayStart : 0;
    }

    // ---------- scoreboard ----------

    public static List<String> sidebarLines(MinecraftClient mc) {
        List<String> out = new ArrayList<>();
        if (mc.world == null) return out;
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return out;
        for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
            Team t = sb.getScoreHolderTeam(e.owner());
            out.add(Team.decorateName(t, e.name()).getString());
        }
        return out;
    }

    /** Primeros code points de una línea (para depurar glifos raros del servidor). */
    public static String codepoints(String line) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < line.length() && n < 4; ) {
            int cp = line.codePointAt(i);
            sb.append(String.format("U+%04X ", cp));
            i += Character.charCount(cp);
            n++;
        }
        return sb.toString().trim();
    }

    private static double readBalance(MinecraftClient mc) {
        List<String> lines = sidebarLines(mc);
        // 1) línea con símbolo de dinero o palabra clave (en cualquier posición de la línea)
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            if (line.indexOf('$') >= 0 || line.indexOf('\uFF04') >= 0 || l.contains("money")
                    || l.contains("balance") || l.contains("dinero") || l.contains("coins")) {
                double v = parseMoney(line);
                if (v >= 0) return v;
            }
        }
        // 2) respaldo: el servidor usa glifos propios; el dinero es el número más grande del scoreboard
        double best = -1;
        for (String line : lines) {
            double v = parseMoney(line);
            if (v > best) best = v;
        }
        return best;
    }

    /** "$ 584M", "1,250", "2.5K" -> double. -1 si falla. */
    public static double parseMoney(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return -1;
        String num = m.group(1).replace(",", "").replaceAll("[.]+$", "");
        try {
            double v = Double.parseDouble(num);
            String suf = m.group(2);
            if (suf != null) {
                switch (suf.toLowerCase(Locale.ROOT)) {
                    case "k" -> v *= 1e3;
                    case "m" -> v *= 1e6;
                    case "b" -> v *= 1e9;
                    case "t" -> v *= 1e12;
                    default -> { }
                }
            }
            return v;
        } catch (Exception e) { return -1; }
    }

    // ---------- formato ----------

    public static String fmt(double v, boolean signed) {
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : (signed && v > 0 ? "+" : "");
        String body;
        if (a >= 1e12) body = compact(a / 1e12) + "T";
        else if (a >= 1e9) body = compact(a / 1e9) + "B";
        else if (a >= 1e6) body = compact(a / 1e6) + "M";
        else if (a >= 1e3) body = compact(a / 1e3) + "K";
        else body = String.format(Locale.ROOT, "%.0f", a);
        return sign + body;
    }

    private static String compact(double x) {
        String t = String.format(Locale.ROOT, "%.2f", x);
        if (t.contains(".")) t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
        return t;
    }

    public static String time(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, ss = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss) : String.format(Locale.ROOT, "%02d:%02d", m, ss);
    }
}
