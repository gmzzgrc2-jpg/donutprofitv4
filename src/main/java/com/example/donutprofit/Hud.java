package com.example.donutprofit;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * HUD: panel de cristal con sombra suave, cabecera con logo y estado, cifra principal animada,
 * gráfico de área suavizado, barra de W/L y toasts de variación.
 */
public final class Hud {
    public record Snapshot(double balance, double today, double gambling, int wins, int losses,
                           boolean sessionActive, double sessionProfit, long sessionMs,
                           double[] graph, long deltaSeq, double lastDelta) {

        public static Snapshot live() {
            Stats s = Tracker.s;
            Config c = Config.get();
            return new Snapshot(Tracker.balance, Tracker.todayChange(), s.gambling, s.wins, s.losses,
                    s.sessionActive, s.sessionProfit,
                    s.sessionActive ? System.currentTimeMillis() - s.sessionStartMs : 0,
                    Tracker.graph(c.showGambling), Tracker.deltaSeq, Tracker.lastDelta);
        }

        /** Datos de ejemplo para la pantalla de ajustes cuando no hay mundo cargado. */
        public static Snapshot demo() {
            long ms = System.currentTimeMillis();
            double t = ms / 1000.0;
            double gamb = 350_000 + 120_000 * Math.sin(t * 0.7);
            double[] g = new double[40];
            for (int i = 0; i < g.length; i++) g[i] = 260_000 * Math.sin(i * 0.33 + 0.4) + i * 9_000;
            g[g.length - 1] = gamb;
            long seq = ms / 3500;
            double delta = (seq % 3 == 0) ? -180_000 : 240_000 + (seq % 5) * 40_000;
            return new Snapshot(584_000_000 + gamb, 1_200_000 + gamb, gamb, 7, 4, true, gamb, ms % 3_600_000,
                    g, seq, delta);
        }
    }

    static final class Anim {
        double cur, tgt;
        float flash;
        boolean init;

        void update(double target, float dt, boolean animate) {
            if (!init || !animate) { cur = target; tgt = target; init = true; return; }
            if (Math.abs(target - tgt) > 0.5) flash = 1f;
            tgt = target;
            double k = 1 - Math.exp(-dt * 9);
            cur += (tgt - cur) * k;
            if (Math.abs(tgt - cur) < 0.5) cur = tgt;
            flash = Math.max(0f, flash - dt * 2.2f);
        }
    }

    private static final class Toast {
        final String text; final int color; float age;
        Toast(String text, int color) { this.text = text; this.color = color; }
    }

    public static final class State {
        float fade, widthAnim;
        long last = System.nanoTime();
        long seenSeq = -1;
        /** Tamaño del panel en píxeles de GUI (ya escalado): la pantalla de ajustes lo usa para no taparlo. */
        public int pw, ph;
        final Anim bal = new Anim(), today = new Anim(), gamb = new Anim(), sess = new Anim();
        final List<Toast> toasts = new ArrayList<>();

        void reset() {
            bal.init = false; today.init = false; gamb.init = false; sess.init = false;
            widthAnim = 0; toasts.clear(); seenSeq = -1; pw = 0; ph = 0;
        }
    }

    public static final State LIVE = new State();
    public static final State CONFIG = new State();

    /** bar: proporción de victorias (0..1) o -1 si la fila no lleva barra. */
    private record Row(String label, String value, int color, float flash, double bar) { }

    private static final int LABEL = 0xFF8B93A1;
    private static final int NEUTRAL = 0xFFE5E7EB;
    private static final int TITLE = 0xFFF3F4F6;
    private static final int POS = 0xFF4ADE80;
    private static final int NEG = 0xFFF87171;
    private static final int IDLE = 0xFF6B7280;

    private Hud() { }

    /** Llamado cada frame desde HudRenderCallback. */
    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof ConfigScreen) return; // la pantalla de ajustes dibuja su propia vista previa
        Config c = Config.get();
        boolean want = c.enabled && mc.player != null && !mc.options.hudHidden;
        draw(ctx, LIVE, Snapshot.live(), want ? 1f : 0f);
    }

    public static void draw(DrawContext ctx, State st, Snapshot sn, float goal) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Config c = Config.get();
        TextRenderer tr = mc.textRenderer;

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - st.last) / 1_000_000_000f);
        st.last = now;

        if (c.animations) {
            float step = dt * 5f;
            st.fade = st.fade < goal ? Math.min(goal, st.fade + step) : Math.max(goal, st.fade - step);
        } else {
            st.fade = goal;
        }
        if (st.fade <= 0.01f) { st.reset(); return; }
        float al = st.fade;
        float rise = goal <= 0f ? 1f : 1f - (float) Math.pow(1f - Math.min(1f, st.fade / goal), 3);

        // ---- valores animados ----
        boolean an = c.animations;
        st.bal.update(Math.max(sn.balance(), 0), dt, an);
        st.today.update(sn.today(), dt, an);
        st.gamb.update(sn.gambling(), dt, an);
        st.sess.update(sn.sessionProfit(), dt, an);

        // ---- toasts ----
        if (st.seenSeq < 0) {
            st.seenSeq = sn.deltaSeq();
        } else if (sn.deltaSeq() != st.seenSeq) {
            st.seenSeq = sn.deltaSeq();
            if (c.toasts && Math.abs(sn.lastDelta()) > 0) {
                st.toasts.add(new Toast(Tracker.fmt(sn.lastDelta(), true), sn.lastDelta() > 0 ? POS : NEG));
                while (st.toasts.size() > 4) st.toasts.remove(0);
            }
        }
        for (Iterator<Toast> it = st.toasts.iterator(); it.hasNext(); ) {
            Toast t = it.next();
            t.age += dt;
            if (t.age > 1.8f) it.remove();
        }

        // ---- contenido ----
        boolean hero = !c.compact && (c.showGambling || c.showToday);
        boolean heroGamb = c.showGambling;
        Anim ha = heroGamb ? st.gamb : st.today;

        List<Row> rows = new ArrayList<>();
        if (c.showBalance)
            rows.add(new Row("Balance", sn.balance() < 0 ? "—" : "$" + Tracker.fmt(st.bal.cur, false), NEUTRAL, st.bal.flash, -1));
        if (c.showToday && !(hero && !heroGamb))
            rows.add(new Row("Hoy", Tracker.fmt(st.today.cur, true), tone(st.today.cur), st.today.flash, -1));
        if (c.showGambling && !hero)
            rows.add(new Row("Gambling", Tracker.fmt(st.gamb.cur, true), tone(st.gamb.cur), st.gamb.flash, -1));
        if (c.showWinLoss) {
            int total = sn.wins() + sn.losses();
            String wl = sn.wins() + " / " + sn.losses() + (total > 0 ? "  " + Math.round(100.0 * sn.wins() / total) + "%" : "");
            rows.add(new Row("W / L", wl, NEUTRAL, 0f, total > 0 ? (double) sn.wins() / total : -1));
        }
        if (c.showSession && sn.sessionActive())
            rows.add(new Row("Sesión", Tracker.fmt(st.sess.cur, true), tone(st.sess.cur), st.sess.flash, -1));
        boolean graph = c.showGraph;

        // ---- medidas ----
        final int pad = 9, rowH = 12, hdrH = 12, logoD = 12, chip = 14;
        boolean active = sn.sessionActive();
        String status = active ? Tracker.time(sn.sessionMs()) : "IDLE";
        int statusW = tr.getWidth(status);
        int pillW = statusW + 18;
        int headerW = logoD + 5 + tr.getWidth("DonutProfit") + 14 + pillW;

        final float heroScale = 1.6f;
        String heroNum = Tracker.fmt(ha.cur, true);
        int heroW = hero ? Math.round(tr.getWidth(heroNum) * heroScale) + 10 + chip : 0;

        int labelW = 0, valueW = 0;
        for (Row r : rows) {
            labelW = Math.max(labelW, tr.getWidth(r.label()));
            valueW = Math.max(valueW, tr.getWidth(r.value()));
        }
        int rowsW = rows.isEmpty() ? 0 : labelW + 16 + valueW;
        int inner = Math.max(Math.max(headerW, heroW), Math.max(rowsW, 112));
        int targetW = inner + pad * 2;
        if (st.widthAnim <= 0f || !c.animations) st.widthAnim = targetW;
        else st.widthAnim += (targetW - st.widthAnim) * (float) (1 - Math.exp(-dt * 12));
        int w = Math.round(st.widthAnim);

        boolean anyBlock = hero || graph || !rows.isEmpty();
        int h = pad + hdrH;
        if (anyBlock) h += 5 + 1 + 6;
        if (hero) h += 22 + 7;
        if (graph) h += 18 + 7;
        int rowsH = 0;
        for (Row r : rows) rowsH += rowH + (r.bar() >= 0 ? 5 : 0);
        if (!rows.isEmpty()) h += rowsH - 3;
        else if (anyBlock) h -= 7;
        h += pad;

        // ---- posición ----
        float sc = c.scale;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        float pw = w * sc, ph = h * sc;
        float px = c.anchor.right() ? sw - pw - c.offsetX : c.offsetX;
        float py = c.anchor.bottom() ? sh - ph - c.offsetY : c.offsetY;
        float slide = (1f - rise) * 10f;
        py += c.anchor.bottom() ? slide : -slide;
        st.pw = Math.round(pw);
        st.ph = Math.round(ph);

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(Math.round(px), Math.round(py));
        m.scale(sc, sc);

        // ---- fondo ----
        int accent = c.accentColor();
        int r = Math.max(0, c.radius);
        float op = c.opacity;
        if (c.shadow) Gfx.shadow(ctx, 0, 0, w, h, r, 6, 0x000000, 0.26f * al);
        Gfx.rrectGrad(ctx, 0, 0, w, h, r,
                Gfx.a(Gfx.mix(0xFF1D1F29, accent, 0.06f), op * al), Gfx.a(0xFF0B0C10, op * al));
        Gfx.outline(ctx, 0, 0, w, h, r, Gfx.a(0x2EFFFFFF, al));
        Gfx.hgrad3(ctx, r + 2, 0, Math.max(2, w - (r + 2) * 2), 1,
                Gfx.a(accent, 0f), Gfx.a(accent, 0.9f * al), Gfx.a(accent, 0f));

        // ---- cabecera ----
        int y = pad;
        Gfx.donut(ctx, pad, y, logoD, accent, 0xFF0F1015, al);
        int titleX = pad + logoD + 5;
        ctx.drawTextWithShadow(tr, "Donut", titleX, y + 2, Gfx.t(accent, al));
        ctx.drawTextWithShadow(tr, "Profit", titleX + tr.getWidth("Donut"), y + 2, Gfx.t(TITLE, al));

        float pulse = active ? 0.55f + 0.45f * (float) Math.sin(now / 2.5e8) : 1f;
        int pillX = w - pad - pillW;
        Gfx.rrect(ctx, pillX, y, pillW, hdrH, hdrH / 2, Gfx.a(active ? Gfx.a(POS, 0.16f) : 0x14FFFFFF, al));
        Gfx.rrect(ctx, pillX + 5, y + 4, 4, 4, 2, Gfx.a(active ? POS : IDLE, al * pulse));
        ctx.drawTextWithShadow(tr, status, pillX + 13, y + 2, Gfx.t(active ? NEUTRAL : IDLE, al));
        y += hdrH;

        int toastY = y;
        if (anyBlock) {
            y += 5;
            ctx.fill(pad, y, w - pad, y + 1, Gfx.a(0x1FFFFFFF, al));
            y += 1 + 6;
        }

        // ---- cifra principal ----
        if (hero) {
            int col = Gfx.mix(tone(ha.cur), 0xFFFFFFFF, ha.flash * 0.7f);
            m.pushMatrix();
            m.translate(pad, y);
            m.scale(0.75f, 0.75f);
            ctx.drawTextWithShadow(tr, heroGamb ? "GAMBLING · HOY" : "PROFIT · HOY", 0, 0, Gfx.t(LABEL, al));
            m.popMatrix();

            m.pushMatrix();
            m.translate(pad, y + 8);
            m.scale(heroScale, heroScale);
            ctx.drawTextWithShadow(tr, heroNum, 0, 0, Gfx.t(col, al));
            m.popMatrix();

            int cx0 = w - pad - chip, cy0 = y + 8;
            Gfx.rrect(ctx, cx0, cy0, chip, chip, 4, Gfx.a(Gfx.a(tone(ha.cur), 0.16f), al));
            arrow(ctx, cx0 + 3, cy0 + 5, ha.cur, Gfx.a(col, al));
            y += 22 + 7;
        }

        // ---- gráfico ----
        if (graph) {
            drawGraph(ctx, sn.graph(), pad, y, w - pad * 2, 18, al);
            y += 18 + 7;
        }

        // ---- filas ----
        for (Row row : rows) {
            ctx.drawTextWithShadow(tr, row.label(), pad, y, Gfx.t(LABEL, al));
            int vc = Gfx.mix(row.color(), 0xFFFFFFFF, row.flash() * 0.7f);
            ctx.drawTextWithShadow(tr, row.value(), w - pad - tr.getWidth(row.value()), y, Gfx.t(vc, al));
            if (row.bar() >= 0) {
                int bw = w - pad * 2;
                int win = (int) Math.round(bw * row.bar());
                if (row.bar() > 0) win = Math.max(2, win);
                Gfx.rrect(ctx, pad, y + 11, bw, 2, 1, Gfx.a(NEG, 0.75f * al));
                if (win > 0) Gfx.rrect(ctx, pad, y + 11, Math.min(win, bw), 2, 1, Gfx.a(POS, al));
                y += rowH + 5;
            } else {
                y += rowH;
            }
        }

        // ---- toasts ----
        boolean right = c.anchor.right();
        for (Toast t : st.toasts) {
            float p = t.age / 1.8f;
            float a2 = (1f - p) * Math.min(1f, t.age * 8f) * al;
            if (a2 <= 0.02f) continue;
            float up = (1f - (float) Math.pow(1f - p, 3)) * 20f;
            float ts = 1.15f;
            int tw = Math.round(tr.getWidth(t.text) * ts);
            int bx = right ? -(tw + 16) : (w + 6);
            int by = Math.round(toastY - 2 - up);
            if (c.shadow) Gfx.shadow(ctx, bx, by, tw + 10, 15, 4, 3, 0x000000, 0.22f * a2);
            Gfx.rrect(ctx, bx, by, tw + 10, 15, 4, Gfx.a(Gfx.mix(0xFF0E0F13, t.color, 0.12f), 0.92f * a2));
            Gfx.outline(ctx, bx, by, tw + 10, 15, 4, Gfx.a(t.color, a2 * 0.6f));
            m.pushMatrix();
            m.translate(bx + 5, by + 3);
            m.scale(ts, ts);
            ctx.drawTextWithShadow(tr, t.text, 0, 0, Gfx.t(t.color, a2));
            m.popMatrix();
        }

        m.popMatrix();
    }

    private static void arrow(DrawContext ctx, int x, int y, double v, int color) {
        if (v > 0.5) {
            for (int i = 0; i < 4; i++) ctx.fill(x + 3 - i, y + i, x + 4 + i, y + i + 1, color);
        } else if (v < -0.5) {
            for (int i = 0; i < 4; i++) ctx.fill(x + i, y + i, x + 7 - i, y + i + 1, color);
        } else {
            ctx.fill(x + 1, y + 1, x + 6, y + 3, color);
        }
    }

    /** Gráfico de área: curva suavizada (interpolación coseno), degradado hacia la línea base y punto final. */
    private static void drawGraph(DrawContext ctx, double[] g, int x, int y, int w, int h, float al) {
        double lo = 0, hi = 0;
        if (g != null) for (double v : g) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi - lo < 1) { lo -= 0.5; hi += 0.5; }
        double span = hi - lo;
        double usable = h - 3;
        int yz = (int) Math.round(y + 1 + hi / span * usable);

        for (int xx = x; xx < x + w; xx += 4) {
            ctx.fill(xx, yz, Math.min(xx + 2, x + w), yz + 1, Gfx.a(0x30FFFFFF, al));
        }
        if (g == null || g.length < 2 || w < 2) return;

        int n = g.length;
        int prev = Integer.MIN_VALUE, lastY = yz, lastCol = POS;
        for (int xx = 0; xx < w; xx++) {
            double pos = (double) xx / (w - 1) * (n - 1);
            int i0 = (int) Math.floor(pos);
            int i1 = Math.min(n - 1, i0 + 1);
            double f = (1 - Math.cos(Math.PI * (pos - i0))) / 2;
            double v = g[i0] + (g[i1] - g[i0]) * f;
            int yy = (int) Math.round(y + 1 + (hi - v) / span * usable);
            int col = v >= 0 ? POS : NEG;

            int a0 = Math.min(yy, yz), a1 = Math.max(yy, yz);
            if (a1 > a0) {
                int split = a0 + (a1 - a0) / 2;
                if (yy <= yz) {
                    ctx.fill(x + xx, a0, x + xx + 1, split, Gfx.a(col, 0.30f * al));
                    ctx.fill(x + xx, split, x + xx + 1, a1, Gfx.a(col, 0.10f * al));
                } else {
                    ctx.fill(x + xx, a0, x + xx + 1, split, Gfx.a(col, 0.10f * al));
                    ctx.fill(x + xx, split, x + xx + 1, a1, Gfx.a(col, 0.30f * al));
                }
            }
            int l = prev == Integer.MIN_VALUE ? yy : Math.min(prev, yy);
            int u = prev == Integer.MIN_VALUE ? yy : Math.max(prev, yy);
            ctx.fill(x + xx, l, x + xx + 1, u + 2, Gfx.a(col, al));
            prev = yy; lastY = yy; lastCol = col;
        }
        int dy = Math.max(y, Math.min(y + h - 4, lastY - 1));
        Gfx.rrect(ctx, x + w - 6, dy - 2, 8, 8, 4, Gfx.a(lastCol, 0.18f * al));
        Gfx.rrect(ctx, x + w - 4, dy, 4, 4, 2, Gfx.a(lastCol, al));
    }

    private static int tone(double v) {
        return v > 0.5 ? POS : (v < -0.5 ? NEG : NEUTRAL);
    }
}
