package com.example.donutprofit;

import net.minecraft.client.gui.DrawContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Primitivas de dibujo: esquinas redondeadas con antialiasing (cobertura por supermuestreo, cacheada por radio),
 * degradados, sombras suaves en anillos, brillos de color y el logo del donut.
 */
final class Gfx {
    private static final Map<Integer, float[][]> COVERAGE = new HashMap<>();
    private static final Map<Integer, int[]> SOLID_FROM = new HashMap<>();

    private Gfx() { }

    // ------------------------------------------------------------------ color

    /** Multiplica el alfa de un color ARGB. */
    static int a(int argb, float mul) {
        int al = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * mul)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** Igual que a(), pero con alfa mínimo para que el texto nunca se pinte opaco por error. */
    static int t(int argb, float mul) {
        int al = Math.max(5, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * mul)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    static int mix(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((c1 >>> 24) & 0xFF) * (1 - t) + ((c2 >>> 24) & 0xFF) * t);
        int r = Math.round(((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = Math.round(((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = Math.round((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ cobertura de esquinas

    /** Cobertura (0..1) de cada píxel de la esquina superior izquierda de un círculo de radio r. */
    private static float[][] cov(int r) {
        float[][] cached = COVERAGE.get(r);
        if (cached != null) return cached;
        final int ss = 4;
        float[][] cv = new float[r][r];
        int[] solid = new int[r];
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < r; j++) {
                int hit = 0;
                for (int sy = 0; sy < ss; sy++) {
                    for (int sx = 0; sx < ss; sx++) {
                        double px = j + (sx + 0.5) / ss - r;
                        double py = i + (sy + 0.5) / ss - r;
                        if (px * px + py * py <= (double) r * r) hit++;
                    }
                }
                cv[i][j] = hit / (float) (ss * ss);
            }
            solid[i] = r;
            for (int j = 0; j < r; j++) {
                if (cv[i][j] >= 0.999f) { solid[i] = j; break; }
            }
        }
        COVERAGE.put(r, cv);
        SOLID_FROM.put(r, solid);
        return cv;
    }

    /** Primera columna totalmente cubierta de cada fila de la esquina. */
    private static int[] solid(int r) {
        cov(r);
        return SOLID_FROM.get(r);
    }

    private static void px4(DrawContext c, int x, int y, int w, int h, int i, int j, int col) {
        c.fill(x + j, y + i, x + j + 1, y + i + 1, col);
        c.fill(x + w - 1 - j, y + i, x + w - j, y + i + 1, col);
        c.fill(x + j, y + h - 1 - i, x + j + 1, y + h - i, col);
        c.fill(x + w - 1 - j, y + h - 1 - i, x + w - j, y + h - i, col);
    }

    // ------------------------------------------------------------------ formas

    /** Rectángulo redondeado con bordes suavizados. */
    static void rrect(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { c.fill(x, y, x + w, y + h, color); return; }
        float[][] cv = cov(r);
        int[] sf = solid(r);
        for (int i = 0; i < r; i++) {
            int f = sf[i];
            c.fill(x + f, y + i, x + w - f, y + i + 1, color);
            c.fill(x + f, y + h - 1 - i, x + w - f, y + h - i, color);
            for (int j = 0; j < f; j++) {
                float al = cv[i][j];
                if (al < 0.02f) continue;
                px4(c, x, y, w, h, i, j, a(color, al));
            }
        }
        c.fill(x, y + r, x + w, y + h - r, color);
    }

    /** Rectángulo redondeado con degradado vertical. */
    static void rrectGrad(DrawContext c, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float[][] cv = r > 0 ? cov(r) : null;
        int[] sf = r > 0 ? solid(r) : null;
        for (int yy = 0; yy < h; yy++) {
            int col = mix(top, bottom, h <= 1 ? 0f : (float) yy / (h - 1));
            int i = yy < r ? yy : (yy >= h - r ? h - 1 - yy : -1);
            if (i < 0) { c.fill(x, y + yy, x + w, y + yy + 1, col); continue; }
            int f = sf[i];
            c.fill(x + f, y + yy, x + w - f, y + yy + 1, col);
            for (int j = 0; j < f; j++) {
                float al = cv[i][j];
                if (al < 0.02f) continue;
                int pc = a(col, al);
                c.fill(x + j, y + yy, x + j + 1, y + yy + 1, pc);
                c.fill(x + w - 1 - j, y + yy, x + w - j, y + yy + 1, pc);
            }
        }
    }

    /** Contorno de 1 px con esquinas suavizadas (anillo = cobertura exterior − cobertura interior). */
    static void outline(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            c.fill(x, y, x + w, y + 1, color);
            c.fill(x, y + h - 1, x + w, y + h, color);
            c.fill(x, y + 1, x + 1, y + h - 1, color);
            c.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        c.fill(x + r, y, x + w - r, y + 1, color);
        c.fill(x + r, y + h - 1, x + w - r, y + h, color);
        c.fill(x, y + r, x + 1, y + h - r, color);
        c.fill(x + w - 1, y + r, x + w, y + h - r, color);
        float[][] outer = cov(r);
        float[][] inner = r > 1 ? cov(r - 1) : null;
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < r; j++) {
                float o = outer[i][j];
                if (o < 0.03f) continue;
                float in;
                if (i < 1 || j < 1) in = 0f;
                else if (inner == null) in = 1f;
                else in = inner[i - 1][j - 1];
                float al = o - in;
                if (al < 0.03f) continue;
                px4(c, x, y, w, h, i, j, a(color, Math.min(1f, al)));
            }
        }
    }

    /**
     * Sombra suave: anillos concéntricos fuera de la forma con caída cuadrática.
     * Al ir solo por fuera no oscurece el interior de paneles translúcidos. Con un color vivo sirve de brillo.
     */
    static void shadow(DrawContext c, int x, int y, int w, int h, int r, int spread, int rgb, float alpha) {
        int base = 0xFF000000 | (rgb & 0xFFFFFF);
        for (int k = 1; k <= spread; k++) {
            float f = 1f - (k - 1f) / spread;
            float al = alpha * f * f;
            if (al < 0.004f) continue;
            outline(c, x - k, y - k, w + 2 * k, h + 2 * k, r + k, a(base, al));
        }
    }

    // ------------------------------------------------------------------ degradados

    /** Degradado horizontal en tiras de 2 px. */
    static void hgrad(DrawContext c, int x, int y, int w, int h, int left, int right) {
        for (int xx = 0; xx < w; xx += 2) {
            c.fill(x + xx, y, x + Math.min(xx + 2, w), y + h, mix(left, right, w <= 1 ? 0f : (float) xx / (w - 1)));
        }
    }

    /** Degradado horizontal de tres paradas (izquierda, centro, derecha). */
    static void hgrad3(DrawContext c, int x, int y, int w, int h, int left, int mid, int right) {
        for (int xx = 0; xx < w; xx += 2) {
            float t = w <= 1 ? 0f : (float) xx / (w - 1);
            int col = t < 0.5f ? mix(left, mid, t * 2f) : mix(mid, right, (t - 0.5f) * 2f);
            c.fill(x + xx, y, x + Math.min(xx + 2, w), y + h, col);
        }
    }

    static void vgrad(DrawContext c, int x, int y, int w, int h, int top, int bottom) {
        for (int yy = 0; yy < h; yy++) {
            c.fill(x, y + yy, x + w, y + yy + 1, mix(top, bottom, h <= 1 ? 0f : (float) yy / (h - 1)));
        }
    }

    // ------------------------------------------------------------------ logo

    /** Mini donut glaseado (masa, glaseado del color de acento, agujero y fideos de colores). */
    static void donut(DrawContext c, int x, int y, int d, int frosting, int hole, float al) {
        rrect(c, x, y, d, d, d / 2, a(0xFFD9A066, al));
        rrect(c, x + 1, y + 1, d - 2, d - 2, (d - 2) / 2, a(frosting, al));
        int hd = Math.max(2, Math.round(d * 0.34f));
        if ((d - hd) % 2 != 0) hd++;
        int o = (d - hd) / 2;
        rrect(c, x + o, y + o, hd, hd, hd / 2, a(hole, al));
        sprinkle(c, x + Math.round(d * 0.20f), y + Math.round(d * 0.32f), a(0xFFFFFFFF, al));
        sprinkle(c, x + Math.round(d * 0.62f), y + Math.round(d * 0.14f), a(0xFF4FD1FF, al));
        sprinkle(c, x + Math.round(d * 0.66f), y + Math.round(d * 0.72f), a(0xFFFFC857, al));
    }

    private static void sprinkle(DrawContext c, int x, int y, int col) {
        c.fill(x, y, x + 2, y + 1, col);
    }
}
