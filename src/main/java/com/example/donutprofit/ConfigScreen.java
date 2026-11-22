package com.example.donutprofit;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pantalla de ajustes: tarjeta de cristal con pestañas, interruptores animados, sliders finos,
 * selector visual de esquina y vista previa en vivo del HUD.
 *
 * Los controles se dibujan a mano; los widgets de Minecraft solo se usan como zonas de clic
 * (invisibles), así que el input, el sonido y la accesibilidad siguen siendo los de vanilla.
 */
public class ConfigScreen extends Screen {
    private static final int CARD_W = 300, PAD = 14, INNER_W = CARD_W - PAD * 2;
    private static final int ROW = 22;
    private static final int TAB_Y = 40, TAB_H = 18, TAB_W = INNER_W / 4;
    private static final int CONTENT_Y = 68, CONTENT_H = 136;
    private static final int FOOT_Y = CONTENT_Y + CONTENT_H + 10, FOOT_H = 18;
    private static final int CARD_H = FOOT_Y + FOOT_H + 12;
    private static final int TRACK_X = 104, TRACK_W = 124;

    private static final int TEXT = 0xFFE5E7EB, MUTED = 0xFF8B93A1, DIM = 0xFF6B7280, NEG = 0xFFF87171;
    private static final String[] TABS = {"Mostrar", "Estilo", "Posición", "General"};

    @FunctionalInterface
    private interface Painter {
        void paint(DrawContext ctx, Entry e, int x, int y, int mx, int my, float dt, float al);
    }

    private static final class Entry {
        final int tab, rel;
        Painter painter;
        float hov, anim;
        Entry(int tab, int rel) { this.tab = tab; this.rel = rel; }
    }

    private record Hit(ClickableWidget w, int dx, int dy, int tab) { }

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private long openedAt, lastNs, confirmUntil;
    private int cardX, cardY, tab;
    private float cardXf, tabAnim = 1f, indicator;
    private String version = "";

    public ConfigScreen(Screen parent) {
        super(Text.literal("DonutProfit"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ construcción

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = System.nanoTime();
        lastNs = System.nanoTime();
        entries.clear();
        hits.clear();
        version = FabricLoader.getInstance().getModContainer("donutprofit")
                .map(mc -> mc.getMetadata().getVersion().getFriendlyString()).orElse("");

        Config c = Config.get();
        cardY = Math.max(4, (this.height - CARD_H) / 2);
        cardXf = targetCardX();
        cardX = Math.round(cardXf);
        indicator = tab * TAB_W;

        // pestañas
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            hit(ButtonWidget.builder(Text.literal(TABS[i]), b -> setTab(idx))
                    .dimensions(0, 0, TAB_W, TAB_H).build(), i * TAB_W, TAB_Y, -1);
        }

        // MOSTRAR
        toggle(0, 0, "Balance", () -> c.showBalance, v -> c.showBalance = v);
        toggle(0, ROW, "Hoy", () -> c.showToday, v -> c.showToday = v);
        toggle(0, ROW * 2, "Gambling", () -> c.showGambling, v -> c.showGambling = v);
        toggle(0, ROW * 3, "W / L", () -> c.showWinLoss, v -> c.showWinLoss = v);
        toggle(0, ROW * 4, "Sesión", () -> c.showSession, v -> c.showSession = v);
        toggle(0, ROW * 5, "Gráfico", () -> c.showGraph, v -> c.showGraph = v);

        // ESTILO
        swatches(1, 0);
        slider(1, ROW, "Opacidad", 0f, 1f, () -> c.opacity, v -> c.opacity = v,
                v -> Math.round(v * 100) + "%");
        slider(1, ROW * 2, "Redondeo", 0f, 10f, () -> (float) c.radius, v -> c.radius = Math.round(v),
                v -> String.valueOf(Math.round(v)));
        toggle(1, ROW * 3, "Sombra", () -> c.shadow, v -> c.shadow = v);
        toggle(1, ROW * 4, "Compacto", () -> c.compact, v -> c.compact = v);

        // POSICIÓN
        anchors(2, 0);
        slider(2, 70, "Escala", 0.5f, 2.5f, () -> c.scale, v -> c.scale = v,
                v -> String.format(Locale.ROOT, "%.2fx", v));
        slider(2, 70 + ROW, "Margen X", 0f, 300f, () -> (float) c.offsetX, v -> c.offsetX = Math.round(v),
                v -> String.valueOf(Math.round(v)));
        slider(2, 70 + ROW * 2, "Margen Y", 0f, 300f, () -> (float) c.offsetY, v -> c.offsetY = Math.round(v),
                v -> String.valueOf(Math.round(v)));

        // GENERAL
        toggle(3, 0, "HUD", () -> c.enabled, v -> c.enabled = v);
        toggle(3, ROW, "Animaciones", () -> c.animations, v -> c.animations = v);
        toggle(3, ROW * 2, "Notificaciones", () -> c.toasts, v -> c.toasts = v);
        commands(3, 76);

        // pie
        hit(ButtonWidget.builder(Text.literal("Reiniciar hoy"), b -> {
            long now = System.currentTimeMillis();
            if (confirmUntil > now) { Tracker.resetToday(); confirmUntil = 0; }
            else confirmUntil = now + 2500;
        }).dimensions(0, 0, 118, FOOT_H).build(), 0, FOOT_Y, -1);
        hit(ButtonWidget.builder(Text.literal("Hecho"), b -> this.close())
                .dimensions(0, 0, 84, FOOT_H).build(), INNER_W - 84, FOOT_Y, -1);
    }

    private void setTab(int t) {
        if (t == tab) return;
        tab = t;
        tabAnim = 0f;
    }

    private void hit(ClickableWidget w, int dx, int dy, int tab) {
        this.addDrawableChild(w);
        hits.add(new Hit(w, dx, dy, tab));
    }

    private Entry entry(int tab, int rel, Painter p) {
        Entry e = new Entry(tab, rel);
        e.painter = p;
        entries.add(e);
        return e;
    }

    private static float ease(float dt, float k) {
        return 1f - (float) Math.exp(-dt * k);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ controles

    private void toggle(int tab, int rel, String name, BooleanSupplier get, Consumer<Boolean> set) {
        Entry e = entry(tab, rel, (ctx, en, x, y, mx, my, dt, al) -> {
            boolean on = get.getAsBoolean();
            en.anim += ((on ? 1f : 0f) - en.anim) * ease(dt, 16f);
            rowBg(ctx, en, x, y, mx, my, dt, al);
            label(ctx, name, x + 8, y + 7, al);
            drawSwitch(ctx, x + INNER_W - 32, y + 5, en.anim, al);
        });
        e.anim = get.getAsBoolean() ? 1f : 0f;
        hit(ButtonWidget.builder(Text.literal(name), b -> set.accept(!get.getAsBoolean()))
                .dimensions(0, 0, INNER_W, ROW).build(), 0, CONTENT_Y + rel, tab);
    }

    private void slider(int tab, int rel, String name, float min, float max,
                        Supplier<Float> get, Consumer<Float> set, Function<Float, String> fmt) {
        Slider sl = new Slider(0, 0, TRACK_W + 8, ROW, name, min, max, get.get(), set);
        Entry e = entry(tab, rel, (ctx, en, x, y, mx, my, dt, al) -> {
            rowBg(ctx, en, x, y, mx, my, dt, al);
            label(ctx, name, x + 8, y + 7, al);
            int acc = Config.get().accentColor();
            int tx = x + TRACK_X, ty = y + ROW / 2 - 2;
            float f = Math.max(0f, Math.min(1f, sl.frac()));
            en.anim += (f - en.anim) * ease(dt, 30f);
            int pos = Math.round(TRACK_W * en.anim);
            Gfx.rrect(ctx, tx, ty, TRACK_W, 4, 2, Gfx.a(0x26FFFFFF, al));
            if (pos > 0) Gfx.rrect(ctx, tx, ty, Math.max(4, pos), 4, 2, Gfx.a(acc, al));
            Gfx.rrect(ctx, tx + pos - 5, ty - 3, 10, 10, 5, Gfx.a(0x55000000, al));
            Gfx.rrect(ctx, tx + pos - 4, ty - 2, 8, 8, 4, Gfx.a(0xFFFFFFFF, al));
            textR(ctx, fmt.apply(sl.val()), x + INNER_W - 8, y + 7, MUTED, al);
        });
        e.anim = sl.frac();
        // el widget se alinea con la pista: SliderWidget deja 4 px de margen a cada lado
        hit(sl, TRACK_X - 4, CONTENT_Y + rel, tab);
    }

    private void swatches(int tab, int rel) {
        final int n = Config.ACCENTS.length;
        float[] sel = new float[n];
        Entry e = entry(tab, rel, (ctx, en, x, y, mx, my, dt, al) -> {
            Config c = Config.get();
            rowBg(ctx, en, x, y, mx, my, dt, al);
            label(ctx, "Color", x + 8, y + 7, al);
            for (int i = 0; i < n; i++) {
                sel[i] += ((c.accent == i ? 1f : 0f) - sel[i]) * ease(dt, 16f);
                int cx = x + INNER_W - 8 - (n - i) * 18 + 9;
                int cy = y + ROW / 2;
                boolean hv = inside(mx, my, cx - 9, y, 18, ROW);
                int d = (hv || sel[i] > 0.5f) ? 12 : 10;
                if (sel[i] > 0.03f) {
                    int rd = d + 4;
                    Gfx.outline(ctx, cx - rd / 2, cy - rd / 2, rd, rd, rd / 2, Gfx.a(0xFFFFFFFF, al * sel[i]));
                }
                Gfx.rrect(ctx, cx - d / 2, cy - d / 2, d, d, d / 2, Gfx.a(Config.ACCENTS[i], al));
            }
        });
        for (int i = 0; i < n; i++) {
            final int idx = i;
            hit(ButtonWidget.builder(Text.literal(Config.ACCENT_NAMES[i]), b -> Config.get().accent = idx)
                    .dimensions(0, 0, 18, ROW).build(), INNER_W - 8 - (n - i) * 18, CONTENT_Y + rel, tab);
        }
    }

    /** Pantalla en miniatura con las cuatro esquinas: se ve dónde quedará el HUD. */
    private void anchors(int tab, int rel) {
        Config.Anchor[] vals = Config.Anchor.values();
        float[] sel = new float[vals.length];
        final int sw = 120, sh = 62, bw = 30, bh = 16;
        entry(tab, rel, (ctx, en, x, y, mx, my, dt, al) -> {
            Config c = Config.get();
            int acc = c.accentColor();
            label(ctx, "Posición", x + 8, y + 8, al);
            ctx.drawTextWithShadow(this.textRenderer, c.anchor.label, x + 8, y + 21, Gfx.t(MUTED, al));

            int sx = x + INNER_W - 8 - sw, sy = y + 4;
            Gfx.rrect(ctx, sx, sy, sw, sh, 6, Gfx.a(0x12FFFFFF, al));
            Gfx.outline(ctx, sx, sy, sw, sh, 6, Gfx.a(0x26FFFFFF, al));
            Gfx.rrect(ctx, sx + (sw - 36) / 2, sy + sh - 8, 36, 4, 2, Gfx.a(0x16FFFFFF, al));

            for (int i = 0; i < vals.length; i++) {
                Config.Anchor a = vals[i];
                sel[i] += ((c.anchor == a ? 1f : 0f) - sel[i]) * ease(dt, 16f);
                int bx = a.right() ? sx + sw - 6 - bw : sx + 6;
                int by = a.bottom() ? sy + sh - 6 - bh : sy + 6;
                boolean hv = inside(mx, my, a.right() ? sx + sw / 2 : sx, a.bottom() ? sy + sh / 2 : sy, sw / 2, sh / 2);
                if (sel[i] > 0.03f) Gfx.shadow(ctx, bx, by, bw, bh, 4, 3, acc, 0.35f * sel[i] * al);
                int fill = Gfx.mix(hv ? 0x30FFFFFF : 0x1CFFFFFF, Gfx.a(acc, 0.95f), sel[i]);
                Gfx.rrect(ctx, bx, by, bw, bh, 4, Gfx.a(fill, al));
                if (sel[i] < 0.97f) Gfx.outline(ctx, bx, by, bw, bh, 4, Gfx.a(0x2AFFFFFF, al * (1f - sel[i])));
            }
        });
        for (int i = 0; i < vals.length; i++) {
            final Config.Anchor a = vals[i];
            hit(ButtonWidget.builder(Text.literal(a.label), b -> Config.get().anchor = a)
                    .dimensions(0, 0, sw / 2, sh / 2).build(),
                    INNER_W - 8 - sw + (a.right() ? sw / 2 : 0),
                    CONTENT_Y + rel + 4 + (a.bottom() ? sh / 2 : 0), tab);
        }
    }

    private void commands(int tab, int rel) {
        String[][] cmds = {
                {"/dprofit session", "iniciar o terminar sesión"},
                {"/dprofit reset", "reiniciar el día"},
                {"/dprofit history", "últimos 7 días"},
                {"/dprofit debug", "ver el scoreboard"},
        };
        entry(tab, rel, (ctx, en, x, y, mx, my, dt, al) -> {
            ctx.drawTextWithShadow(this.textRenderer, "COMANDOS", x + 8, y, Gfx.t(MUTED, al));
            int tw = this.textRenderer.getWidth("COMANDOS");
            ctx.fill(x + 8 + tw + 6, y + 4, x + INNER_W - 8, y + 5, Gfx.a(0x22FFFFFF, al));
            for (int i = 0; i < cmds.length; i++) {
                int ly = y + 14 + i * 11;
                ctx.drawTextWithShadow(this.textRenderer, cmds[i][0], x + 8, ly, Gfx.t(TEXT, al));
                ctx.drawTextWithShadow(this.textRenderer, cmds[i][1], x + 118, ly, Gfx.t(DIM, al));
            }
        });
    }

    // ------------------------------------------------------------------ piezas de dibujo

    private void rowBg(DrawContext ctx, Entry en, int x, int y, int mx, int my, float dt, float al) {
        boolean hover = inside(mx, my, x, y, INNER_W, ROW);
        en.hov += ((hover ? 1f : 0f) - en.hov) * ease(dt, 18f);
        if (en.hov > 0.02f) Gfx.rrect(ctx, x, y, INNER_W, ROW, 5, Gfx.a(0x16FFFFFF, al * en.hov));
    }

    private void label(DrawContext ctx, String s, int x, int y, float al) {
        ctx.drawTextWithShadow(this.textRenderer, s, x, y, Gfx.t(TEXT, al));
    }

    private void textR(DrawContext ctx, String s, int right, int y, int color, float al) {
        ctx.drawTextWithShadow(this.textRenderer, s, right - this.textRenderer.getWidth(s), y, Gfx.t(color, al));
    }

    private void textC(DrawContext ctx, String s, int cx, int y, int color, float al) {
        ctx.drawTextWithShadow(this.textRenderer, s, cx - this.textRenderer.getWidth(s) / 2, y, Gfx.t(color, al));
    }

    private static void drawSwitch(DrawContext ctx, int x, int y, float t, float al) {
        int acc = Config.get().accentColor();
        Gfx.rrect(ctx, x, y, 24, 12, 6, Gfx.a(Gfx.mix(0xFF2B2E3A, acc, t), al));
        Gfx.outline(ctx, x, y, 24, 12, 6, Gfx.a(0x1FFFFFFF, al));
        int kx = x + 2 + Math.round(t * 12);
        Gfx.rrect(ctx, kx, y + 2, 8, 8, 4, Gfx.a(Gfx.mix(0xFFB8BDC8, 0xFFFFFFFF, t), al));
    }

    /** Sin blur: la vista previa del HUD se dibuja nítida en render(). */
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    // ------------------------------------------------------------------ render

    /** Si el HUD quedaría tapado por la tarjeta, ésta se desliza suavemente al lado contrario. */
    private int targetCardX() {
        int centered = (this.width - CARD_W) / 2;
        Config c = Config.get();
        Hud.State st = Hud.CONFIG;
        if (!c.enabled || st.pw <= 0) return centered;
        int extra = 64; // espacio para los toasts
        if (!c.anchor.right()) {
            int edge = c.offsetX + st.pw + extra;
            if (centered >= edge) return centered;
            return Math.max(centered, Math.min(edge, this.width - CARD_W - 8));
        }
        int edge = this.width - c.offsetX - st.pw - extra;
        if (centered + CARD_W <= edge) return centered;
        return Math.min(centered, Math.max(8, edge - CARD_W));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Config c = Config.get();
        MinecraftClient mc = MinecraftClient.getInstance();

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;

        float t = Math.min(1f, (now - openedAt) / 320_000_000f);
        float e = 1f - (1f - t) * (1f - t) * (1f - t);
        int slide = Math.round((1f - e) * 14);
        int accent = c.accentColor();

        // atenuado del fondo
        ctx.fill(0, 0, this.width, this.height, Gfx.a(0x88000000, e));

        // vista previa en vivo del HUD (datos reales si hay mundo, de ejemplo si no)
        boolean live = mc.world != null && Tracker.balance >= 0;
        Hud.draw(ctx, Hud.CONFIG, live ? Hud.Snapshot.live() : Hud.Snapshot.demo(), c.enabled ? 1f : 0.35f);

        // animaciones de la tarjeta
        cardXf += (targetCardX() - cardXf) * ease(dt, 7f);
        cardX = Math.round(cardXf);
        tabAnim = Math.min(1f, tabAnim + dt * 5f);
        float te = 1f - (1f - tabAnim) * (1f - tabAnim) * (1f - tabAnim);
        indicator += (tab * TAB_W - indicator) * ease(dt, 16f);

        // zonas de clic: siguen a la tarjeta y solo están activas en la pestaña visible
        for (Hit h : hits) {
            boolean vis = h.tab() < 0 || h.tab() == tab;
            h.w().visible = vis;
            h.w().active = vis;
            h.w().setX(cardX + PAD + h.dx());
            h.w().setY(cardY + slide + h.dy());
        }

        int x = cardX, y = cardY + slide;
        var m = ctx.getMatrices();

        // ---- tarjeta ----
        Gfx.shadow(ctx, x, y, CARD_W, CARD_H, 8, 8, 0x000000, 0.5f * e);
        Gfx.rrectGrad(ctx, x, y, CARD_W, CARD_H, 8, Gfx.a(0xFF181A22, e), Gfx.a(0xFF101117, e));
        Gfx.rrectGrad(ctx, x + 1, y + 1, CARD_W - 2, 46, 7, Gfx.a(accent, 0.10f * e), Gfx.a(accent, 0f));
        Gfx.outline(ctx, x, y, CARD_W, CARD_H, 8, Gfx.a(0x2EFFFFFF, e));
        Gfx.hgrad3(ctx, x + 10, y, CARD_W - 20, 1, Gfx.a(accent, 0f), Gfx.a(accent, 0.9f * e), Gfx.a(accent, 0f));

        // ---- cabecera ----
        Gfx.donut(ctx, x + PAD, y + 13, 16, accent, 0xFF14161C, e);
        m.pushMatrix();
        m.translate(x + PAD + 22, y + 13);
        m.scale(1.25f, 1.25f);
        ctx.drawTextWithShadow(this.textRenderer, "Donut", 0, 0, Gfx.t(accent, e));
        ctx.drawTextWithShadow(this.textRenderer, "Profit", this.textRenderer.getWidth("Donut"), 0, Gfx.t(0xFFFFFFFF, e));
        m.popMatrix();
        m.pushMatrix();
        m.translate(x + PAD + 22, y + 28);
        m.scale(0.8f, 0.8f);
        ctx.drawTextWithShadow(this.textRenderer, "Ajustes · los cambios se ven en vivo", 0, 0, Gfx.t(MUTED, e));
        m.popMatrix();
        if (!version.isEmpty()) textR(ctx, "v" + version, x + CARD_W - PAD, y + 14, DIM, e);

        // ---- pestañas ----
        int tx0 = x + PAD, ty0 = y + TAB_Y;
        Gfx.rrect(ctx, tx0, ty0, INNER_W, TAB_H, 6, Gfx.a(0x14FFFFFF, e));
        int ix = tx0 + Math.round(indicator);
        Gfx.rrect(ctx, ix + 2, ty0 + 2, TAB_W - 4, TAB_H - 4, 5, Gfx.a(accent, 0.22f * e));
        Gfx.outline(ctx, ix + 2, ty0 + 2, TAB_W - 4, TAB_H - 4, 5, Gfx.a(accent, 0.55f * e));
        for (int i = 0; i < TABS.length; i++) {
            boolean hv = inside(mouseX, mouseY, tx0 + i * TAB_W, ty0, TAB_W, TAB_H);
            int col = i == tab ? 0xFFFFFFFF : (hv ? 0xFFC9CED8 : MUTED);
            textC(ctx, TABS[i], tx0 + i * TAB_W + TAB_W / 2, ty0 + 5, col, e);
        }

        // ---- contenido de la pestaña (fundido + ligero desplazamiento) ----
        float ca = e * te;
        int oy = Math.round((1f - te) * 5f);
        for (Entry en : entries) {
            if (en.tab == tab) en.painter.paint(ctx, en, x + PAD, y + CONTENT_Y + en.rel + oy, mouseX, mouseY, dt, ca);
        }

        // ---- pie ----
        ctx.fill(x + PAD, y + FOOT_Y - 6, x + CARD_W - PAD, y + FOOT_Y - 5, Gfx.a(0x1FFFFFFF, e));

        boolean confirm = confirmUntil > System.currentTimeMillis();
        int rx = x + PAD, ry = y + FOOT_Y;
        boolean rh = inside(mouseX, mouseY, rx, ry, 118, FOOT_H);
        int rbg = confirm ? Gfx.a(NEG, 0.28f) : (rh ? 0x26FFFFFF : 0x14FFFFFF);
        Gfx.rrect(ctx, rx, ry, 118, FOOT_H, 6, Gfx.a(rbg, e));
        if (confirm) Gfx.outline(ctx, rx, ry, 118, FOOT_H, 6, Gfx.a(NEG, 0.6f * e));
        textC(ctx, confirm ? "¿Seguro?" : "Reiniciar hoy", rx + 59, ry + 5, confirm ? NEG : (rh ? 0xFFFFFFFF : MUTED), e);

        int dx = x + CARD_W - PAD - 84, dy = y + FOOT_Y;
        boolean dh = inside(mouseX, mouseY, dx, dy, 84, FOOT_H);
        if (dh) Gfx.shadow(ctx, dx, dy, 84, FOOT_H, 6, 3, accent, 0.30f * e);
        Gfx.rrect(ctx, dx, dy, 84, FOOT_H, 6, Gfx.a(accent, (dh ? 1f : 0.88f) * e));
        textC(ctx, "Hecho", dx + 42, dy + 5, 0xFF14151A, e);
    }

    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        Config.save();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    // ------------------------------------------------------------------ slider (zona de clic + valor)

    private static final class Slider extends SliderWidget {
        private final String name;
        private final float min, max;
        private final Consumer<Float> setter;

        Slider(int x, int y, int w, int h, String name, float min, float max, float val, Consumer<Float> setter) {
            super(x, y, w, h, Text.empty(), (val - min) / (max - min));
            this.name = name; this.min = min; this.max = max; this.setter = setter;
            updateMessage();
        }

        float frac() { return (float) this.value; }

        float val() { return min + frac() * (max - min); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.valueOf(name)));
        }

        @Override
        protected void applyValue() { setter.accept(val()); }
    }
}
