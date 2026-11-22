package com.example.donutprofit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Estadísticas persistentes (config/donutprofit-stats.json). */
public final class Stats {
    public static final class Day {
        public double change;
        public double gambling;
        public int wins;
        public int losses;
    }

    public String day = "";
    public double dayStart = -1;
    public double lastBalance = -1;
    public double gambling = 0;
    public int wins = 0;
    public int losses = 0;
    public double bestWin = 0;
    public double worstLoss = 0;
    public Map<String, Day> history = new LinkedHashMap<>();

    // Estado de sesión (no se guarda)
    public transient boolean sessionActive;
    public transient double sessionProfit;
    public transient int sessionWins;
    public transient int sessionLosses;
    public transient long sessionStartMs;
}
