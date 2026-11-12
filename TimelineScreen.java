package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Editor de timeline em painel inferior: o mundo continua visível acima,
 * mostrando em tempo real a câmera do keyframe/playhead.
 */
public final class TimelineScreen extends Screen {
    // ---- layout ----
    private static final int BASE_PANEL_H = 160, ROW_STEP = 22, GUTTER = 76, PAD = 8;
    private static final int LANE_H = 56, SPEED_H = 46, RULER_H = 16, KEY_R = 7, SPEED_R = 4;

    // ---- tema ----
    private static final int BG = 0xEE12151C, BORDER = 0xFF2A3142, LANE_BG = 0xFF181C26, GRID = 0xFF222836;
    private static final int TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, DISABLED = 0xFF565E70, ERROR = 0xFFFF6B6B;
    private static final int ACCENT = 0xFF4DA3FF, ACCENT_DIM = 0xFF1F3F66, PLAYHEAD = 0xFFFF4D5E;
    private static final int BTN = 0xFF202636, BTN_HOVER = 0xFF2B3345;
    private static final int AMBER = 0xFFE8A33D, AMBER_AREA = 0x22E8A33D;

    static int colorOf(Easing e) {
        return switch (e) {
            case SMOOTH -> 0xFF3FB8AF;
            case LINEAR -> 0xFF6C8EF5;
            case EASE -> 0xFFF2B84B;
            case HOLD -> 0xFFE0626A;
        };
    }

    private static final double[] SPEED_PRESETS = {0.25, 0.5, 1.0, 2.0, 4.0};
    private static final long DOUBLE_CLICK_MS = 350;

    // ---- estado ----
    private final CameraDirector dir = CameraDirector.get();
    private final Timeline tl = dir.timeline;
    private final Selection sel = dir.selection;
    private final TimelineView view = new TimelineView();
    private final List<Btn> buttons = new ArrayList<>();
    private boolean snap = true;
    private boolean speedOn = false;
    private boolean needsFit = true;
    private int toolbarRows = 1;
    private int flowX, flowRow;

    private enum Mode { NONE, SCRUB, KEYS, BOX, PAN, SPEED }
    private Mode mode = Mode.NONE;
    private double startX, startY, curX, curY;
    private long grabOffsetMs, anchorTimeMs, dragDelta;
    private boolean dragValid = true, shiftAtPress;

    // ponto de velocidade selecionado / em arraste
    private Long selSpeed;
    private long speedTime;
    private double speedVal;
    private boolean speedDragValid = true;
    private double speedGrabDx, speedGrabDy;
    private long lastClickMs;
    private double lastClickX, lastClickY;

    public TimelineScreen() {
        super(Text.literal("TimeScute"));
    }

    void requestFit() { needsFit = true; }

    // ================= ciclo de vida =================

    @Override
    protected void init() {
        dir.setEditorOpen(true);
        view.setWidth(width - GUTTER - PAD);
        buildButtons();
        if (needsFit) { view.fit(tl.durationMs()); needsFit = false; }
    }

    @Override
    public void removed() {
        dir.setEditorOpen(false);
        dir.autosave();
    }

    @Override
    public boolean shouldPause() {
        return false; // o mundo segue vivo durante o preview
    }

    // ================= geometria =================

    private int panelH() { return BASE_PANEL_H + ROW_STEP * (toolbarRows - 1) + (speedOn ? SPEED_H : 0); }
    private int panelY() { return height - panelH(); }
    private int toolbarY(int row) { return panelY() + 7 + row * ROW_STEP; }
    private int rulerY() { return panelY() + 10 + toolbarRows * ROW_STEP; }
    private int laneY() { return rulerY() + RULER_H; }
    private int speedY() { return laneY() + LANE_H; }
    private int lanesBottom() { return speedY() + (speedOn ? SPEED_H : 0); }
    private int inspY() { return lanesBottom() + 8; }
    private int x0() { return GUTTER; }
    private int x1() { return width - PAD; }
    private int sx(long ms) { return x0() + (int) Math.round(view.xOf(ms)); }

    private boolean inTimelineX(double mx) { return mx >= x0() && mx <= x1(); }
    private boolean inRuler(double mx, double my) { return inTimelineX(mx) && my >= rulerY() && my < laneY(); }
    private boolean inLane(double mx, double my) { return inTimelineX(mx) && my >= laneY() && my < laneY() + LANE_H; }
    private boolean inSpeed(double mx, double my) { return speedOn && inTimelineX(mx) && my >= speedY() && my < speedY() + SPEED_H; }

    // ---- escala vertical da trilha de velocidade (logarítmica: 0.5x e 2x ficam à mesma distância de 1x) ----
    private int spTop() { return speedY() + 7; }
    private int spBot() { return speedY() + SPEED_H - 7; }

    private int speedToY(double v) {
        double lo = Math.log(SpeedTrack.MIN), hi = Math.log(SpeedTrack.MAX);
        double t = (Math.log(SpeedTrack.clampSpeed(v)) - lo) / (hi - lo);
        return (int) Math.round(spBot() - t * (spBot() - spTop()));
    }

    private double yToSpeed(double y) {
        double lo = Math.log(SpeedTrack.MIN), hi = Math.log(SpeedTrack.MAX);
        double t = Math.max(0, Math.min(1, (spBot() - y) / (spBot() - spTop())));
        return Math.exp(lo + t * (hi - lo));
    }

    /** Com snap ligado, gruda em 0.25x/0.5x/1x/2x/4x quando perto; senão arredonda a 2 casas. */
    private double snapSpeed(double v) {
        v = SpeedTrack.clampSpeed(v);
        if (snap) for (double p : SPEED_PRESETS) if (Math.abs(Math.log(v / p)) < 0.07) return p;
        return Math.round(v * 100.0) / 100.0;
    }

    // ================= botões =================

    private static final class Btn {
        final Supplier<String> label;
        final Runnable action;
        final BooleanSupplier enabled, active;
        final Supplier<String> tip;
        int group, row, x, w, h = 18; // group 0 = barra de ferramentas; 1 = inspetor

        Btn(Supplier<String> label, Runnable action, BooleanSupplier enabled, BooleanSupplier active, Supplier<String> tip) {
            this.label = label; this.action = action; this.enabled = enabled; this.active = active; this.tip = tip;
        }
    }

    private int by(Btn b) { return b.group == 0 ? toolbarY(b.row) : inspY(); }

    private boolean hit(Btn b, double mx, double my) {
        int y = by(b);
        return mx >= b.x && mx < b.x + b.w && my >= y && my < y + b.h;
    }

    /** Coloca o botão na linha atual; quebra para a próxima linha se não couber. */
    private void flow(Btn b, int minW, int gapAfter) {
        int readoutW = textRenderer.getWidth("00:00.000 / 00:00.000") + 10;
        b.w = Math.max(minW, textRenderer.getWidth(b.label.get()) + 14);
        int limit = flowRow == 0 ? width - PAD - readoutW : width - PAD;
        if (flowX + b.w > limit && flowX > PAD) { flowRow++; flowX = PAD; }
        b.group = 0;
        b.row = flowRow;
        b.x = flowX;
        flowX += b.w + gapAfter;
        buttons.add(b);
    }

    private void buildButtons() {
        buttons.clear();
        flowX = PAD;
        flowRow = 0;
        BooleanSupplier always = () -> true, never = () -> false;
        BooleanSupplier hasKeys = () -> !tl.path().isEmpty();

        flow(new Btn(() -> "Início", () -> { dir.pause(); dir.setPlayhead(0); view.follow(0); },
                always, never, () -> "Volta o playhead ao começo (Home)"), 0, 4);
        flow(new Btn(() -> dir.isPlaying() ? "Pausar" : "Play", dir::togglePlay,
                hasKeys, dir::isPlaying, () -> "Reproduz o trajeto (Espaço)"), 56, 4);
        flow(new Btn(() -> "Capturar", this::captureHere, always, never,
                () -> "Cria um keyframe com a câmera atual, no playhead (K)"), 0, 10);
        flow(new Btn(() -> "Desfazer", tl::undo, tl::canUndo, never,
                () -> tl.undoLabel().map(s -> "Desfazer: " + s + " (Ctrl+Z)").orElse("Nada para desfazer")), 0, 4);
        flow(new Btn(() -> "Refazer", tl::redo, tl::canRedo, never,
                () -> tl.redoLabel().map(s -> "Refazer: " + s + " (Ctrl+Y)").orElse("Nada para refazer")), 0, 10);
        flow(new Btn(() -> "Snap", () -> snap = !snap, always, () -> snap,
                () -> "Alinha keyframes à grade e velocidades a 0.25x/0.5x/1x/2x/4x"), 0, 4);
        flow(new Btn(() -> "Guias", dir::toggleGuides, always, dir::showGuides,
                () -> "Mostra o trajeto e as câmeras no mundo 3D"), 0, 4);
        flow(new Btn(() -> "Velocidade", () -> { speedOn = !speedOn; buildButtons(); }, always, () -> speedOn,
                () -> "Mostra a trilha de velocidade (câmera lenta/rápida)"), 0, 4);
        flow(new Btn(() -> "Ajustar", () -> view.fit(tl.durationMs()), always, never,
                () -> "Enquadra a timeline inteira"), 0, 10);
        flow(new Btn(() -> "Novo", this::newPath, always, never,
                () -> "Começa um trajeto vazio (Ctrl+N; dá para desfazer)"), 0, 4);
        flow(new Btn(() -> "Abrir", this::openDialog, always, never,
                () -> "Abre um trajeto salvo (Ctrl+O)"), 0, 4);
        flow(new Btn(() -> "Salvar", () -> save(false), always, never,
                () -> dir.pathName() == null ? "Salva o trajeto (Ctrl+S)"
                        : "Salva em \"" + dir.pathName() + "\" (Ctrl+S). Salvar como: Ctrl+Shift+S"), 0, 4);
        toolbarRows = flowRow + 1;

        // interpolação: alinhada à direita, na linha do inspetor
        int rx = width - PAD;
        Easing[] all = Easing.values();
        for (int i = all.length - 1; i >= 0; i--) {
            Easing e = all[i];
            Btn b = new Btn(() -> e.label, () -> tl.setEasing(sel.ids(), e), () -> !sel.isEmpty(),
                    () -> selectionEasing().filter(s -> s == e).isPresent(), () -> e.description);
            b.group = 1;
            b.w = textRenderer.getWidth(e.label) + 14;
            b.x = rx - b.w;
            rx = b.x - 4;
            buttons.add(b);
        }
    }

    private Optional<Easing> selectionEasing() {
        Easing found = null;
        for (long id : sel.ids()) {
            Optional<Keyframe> k = tl.path().byId(id);
            if (k.isEmpty()) continue;
            if (found == null) found = k.get().easing();
            else if (found != k.get().easing()) return Optional.empty();
        }
        return Optional.ofNullable(found);
    }

    // ================= ações =================

    private void captureHere() {
        if (client == null || client.player == null) return;
        Keyframe k = tl.capture(dir.playheadMs(), dir.captureCurrent(client));
        sel.set(k.id());
        selSpeed = null;
    }

    private void newPath() {
        tl.clear();
        sel.clear();
        selSpeed = null;
        dir.pause();
        dir.setPlayhead(0);
        dir.setPathName(null);
        dir.toast("Novo trajeto (Ctrl+Z desfaz)", false);
    }

    private void openDialog() {
        client.setScreen(new OpenPathScreen(this));
    }

    private void save(boolean saveAs) {
        if (saveAs || dir.pathName() == null) {
            client.setScreen(new SavePathScreen(this));
            return;
        }
        try {
            PathFiles.save(dir.pathsDir(), dir.pathName(), tl);
            dir.toast("Salvo: " + dir.pathName(), false);
        } catch (Exception e) {
            dir.toast("Não foi possível salvar: " + e.getMessage(), true);
        }
    }

    private void jumpToKeyframe(int direction) {
        long now = dir.playheadMs();
        Keyframe target = null;
        for (Keyframe k : tl.path().all()) {
            if (direction > 0 && k.timeMs() > now + 1) { target = k; break; }
            if (direction < 0 && k.timeMs() < now - 1) target = k;
        }
        if (target != null) {
            dir.pause();
            dir.setPlayhead(target.timeMs());
            sel.set(target.id());
            selSpeed = null;
            view.follow(target.timeMs());
        }
    }

    private void scrubTo(double mx) {
        dir.pause();
        dir.setPlayhead(view.msAt(mx - x0()));
    }

    private Optional<SpeedKey> hitSpeed(double mx, double my) {
        SpeedKey best = null;
        double bestD = Double.MAX_VALUE;
        for (SpeedKey k : tl.speed().all()) {
            double d = Math.hypot(sx(k.timeMs()) - mx, speedToY(k.speed()) - my);
            if (d <= SPEED_R + 4 && d < bestD) { best = k; bestD = d; }
        }
        return Optional.ofNullable(best);
    }

    // ================= desenho =================

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // sem renderBackground: queremos ver o mundo/câmera de preview
        if (selSpeed != null && tl.speed().byId(selSpeed).isEmpty()) selSpeed = null;
        if (dir.isPlaying()) view.follow(dir.playheadMs());

        ctx.fill(0, panelY(), width, height, BG);
        ctx.fill(0, panelY(), width, panelY() + 1, BORDER);

        List<Text> tooltip = null;
        tooltip = drawToolbar(ctx, mx, my, tooltip);
        drawRuler(ctx);
        tooltip = drawLane(ctx, mx, my, tooltip);
        if (speedOn) tooltip = drawSpeedLane(ctx, mx, my, tooltip);
        tooltip = drawInspector(ctx, mx, my, tooltip);
        drawPlayhead(ctx);
        drawFooter(ctx);

        if (tooltip != null) ctx.drawTooltip(textRenderer, tooltip, mx, my);
    }

    private void drawFooter(DrawContext ctx) {
        int fy = height - 12;
        if (dir.hasToast()) {
            ctx.drawTextWithShadow(textRenderer, dir.toastText(), PAD, fy, dir.toastIsError() ? ERROR : ACCENT);
        } else {
            ctx.drawTextWithShadow(textRenderer,
                    "K capturar | Espaço play | Del apagar | Ctrl+Z desfaz | Ctrl+S salva | Roda zoom | Shift+Roda rola | Botão dir.: interpolação",
                    PAD, fy, DISABLED);
        }
    }

    private List<Text> drawToolbar(DrawContext ctx, int mx, int my, List<Text> tip) {
        for (Btn b : buttons) if (b.group == 0) tip = drawButton(ctx, b, mx, my, tip);
        String time = TimelineView.format(dir.playheadMs());
        if (width >= 700) time += " / " + TimelineView.format(tl.path().lastTimeMs());
        ctx.drawTextWithShadow(textRenderer, time, width - PAD - textRenderer.getWidth(time), toolbarY(0) + 5, TEXT);
        String name = dir.pathName() == null ? "(sem nome)" : dir.pathName();
        if (toolbarRows == 1 && width >= 1000)
            ctx.drawTextWithShadow(textRenderer, name, width - PAD - 160 - textRenderer.getWidth(name), toolbarY(0) + 5, MUTED);
        return tip;
    }

    private List<Text> drawButton(DrawContext ctx, Btn b, int mx, int my, List<Text> tip) {
        int y = by(b);
        boolean over = hit(b, mx, my), en = b.enabled.getAsBoolean(), hov = en && over, act = b.active.getAsBoolean();
        ctx.fill(b.x, y, b.x + b.w, y + b.h, act ? ACCENT_DIM : hov ? BTN_HOVER : BTN);
        if (act) ctx.drawBorder(b.x, y, b.w, b.h, ACCENT);
        ctx.drawCenteredTextWithShadow(textRenderer, b.label.get(), b.x + b.w / 2, y + 5, en ? TEXT : DISABLED);
        if (over && b.tip != null) return List.of(Text.literal(b.tip.get()));
        return tip;
    }

    private void drawRuler(DrawContext ctx) {
        int ry = rulerY();
        ctx.fill(x0(), ry, x1(), ry + RULER_H, 0xFF141821);
        ctx.enableScissor(x0(), ry, x1(), ry + RULER_H);
        long step = view.gridStepMs();
        long first = Math.max(0, (long) Math.floor(view.originMs() / step) * step);
        for (long t = first; ; t += step) {
            int x = sx(t);
            if (x > x1()) break;
            ctx.fill(x, ry + RULER_H - 6, x + 1, ry + RULER_H, MUTED);
            int half = sx(t + step / 2);
            ctx.fill(half, ry + RULER_H - 3, half + 1, ry + RULER_H, DISABLED);
            String label = TimelineView.format(t);
            ctx.drawText(textRenderer, step >= 1000 ? label.substring(0, 5) : label, x + 3, ry + 2, MUTED, false);
        }
        ctx.disableScissor();
    }

    private void drawGrid(DrawContext ctx, int top, int h) {
        long step = view.gridStepMs();
        long first = Math.max(0, (long) Math.floor(view.originMs() / step) * step);
        for (long t = first; sx(t) <= x1(); t += step) ctx.fill(sx(t), top, sx(t) + 1, top + h, GRID);
    }

    private List<Text> drawLane(DrawContext ctx, int mx, int my, List<Text> tip) {
        int ly = laneY(), cy = ly + LANE_H / 2;
        ctx.fill(0, ly, width, ly + LANE_H, LANE_BG);
        ctx.drawTextWithShadow(textRenderer, "CÂMERA", PAD, cy - 4, MUTED);

        ctx.enableScissor(x0(), ly, x1(), ly + LANE_H);
        drawGrid(ctx, ly, LANE_H);

        List<Keyframe> keys = tl.path().all();

        // faixa entre keyframes, colorida pela interpolação (dá pra "ler" o trajeto de relance)
        for (int i = 0; i + 1 < keys.size(); i++) {
            Keyframe a = keys.get(i), b = keys.get(i + 1);
            int xa = sx(a.timeMs()), xb = sx(b.timeMs());
            int c = (colorOf(a.easing()) & 0x00FFFFFF) | 0x66000000;
            if (a.easing() == Easing.HOLD) {
                ctx.fill(xa, cy - 1, xb, cy + 1, c);
                ctx.fill(xb - 1, cy - 8, xb + 1, cy + 8, c);
            } else {
                ctx.fill(xa, cy - 3, xb, cy + 3, c);
            }
        }

        // keyframes
        boolean dragging = mode == Mode.KEYS && dragDelta != 0;
        for (Keyframe k : keys) {
            int x = sx(k.timeMs());
            boolean selected = sel.contains(k.id());
            boolean ghosted = dragging && selected;
            if (selected) diamond(ctx, x, cy, KEY_R + 2, ghosted ? 0x55FFFFFF : 0xFFFFFFFF);
            diamond(ctx, x, cy, KEY_R, ghosted ? 0x66000000 | (colorOf(k.easing()) & 0xFFFFFF) : colorOf(k.easing()));
        }
        if (dragging) {
            int c = dragValid ? 0xCCFFFFFF : 0xCCFF4D5E;
            for (long id : sel.ids())
                tl.path().byId(id).ifPresent(k -> diamond(ctx, sx(k.timeMs() + dragDelta), cy, KEY_R, c));
        }

        // caixa de seleção
        if (mode == Mode.BOX && Math.hypot(curX - startX, curY - startY) >= 4) {
            int bx0 = (int) Math.min(startX, curX), bx1 = (int) Math.max(startX, curX);
            ctx.fill(bx0, ly + 2, bx1, ly + LANE_H - 2, 0x334DA3FF);
            ctx.drawBorder(bx0, ly + 2, bx1 - bx0, LANE_H - 4, ACCENT);
        }

        ctx.disableScissor();

        if (keys.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    "Nenhum keyframe ainda. Posicione a câmera e aperte K (ou clique em Capturar).",
                    (x0() + x1()) / 2, cy - 4, MUTED);
        }

        // tooltip do keyframe sob o mouse
        if (mode == Mode.NONE && inLane(mx, my)) {
            Optional<Keyframe> h = view.hit(tl.path(), mx - x0(), KEY_R + 3);
            if (h.isPresent()) {
                Keyframe k = h.get();
                CameraState s = k.state();
                tip = List.of(
                        Text.literal("Keyframe em " + TimelineView.format(k.timeMs())),
                        Text.literal("Interpolação: " + k.easing().label),
                        Text.literal(String.format(Locale.ROOT, "Pos %.1f  %.1f  %.1f", s.x(), s.y(), s.z())),
                        Text.literal(String.format(Locale.ROOT, "FOV %.0f", s.fov())));
            }
        }
        return tip;
    }

    private List<Text> drawSpeedLane(DrawContext ctx, int mx, int my, List<Text> tip) {
        int sy = speedY();
        ctx.fill(0, sy, width, sy + SPEED_H, 0xFF151922);
        ctx.fill(0, sy, width, sy + 1, BORDER);
        ctx.drawTextWithShadow(textRenderer, "VELOCIDADE", PAD, sy + 8, MUTED);
        String now = String.format(Locale.ROOT, "%.2fx", tl.speed().speedAt(dir.playheadMs()));
        ctx.drawTextWithShadow(textRenderer, now, PAD, sy + 22, TEXT);

        ctx.enableScissor(x0(), sy, x1(), sy + SPEED_H);
        drawGrid(ctx, sy, SPEED_H);

        // linha de referência: 1x
        int y1x = speedToY(1.0);
        ctx.fill(x0(), y1x, x1(), y1x + 1, 0xFF2F3648);

        // curva (área preenchida + linha)
        int base = sy + SPEED_H - 1;
        for (int px = x0(); px < x1(); px += 2) {
            double v = tl.speed().speedAt(view.msAt(px - x0()));
            int y = speedToY(v);
            ctx.fill(px, y, px + 2, base, AMBER_AREA);
            ctx.fill(px, y, px + 2, y + 1, AMBER);
        }

        // pontos
        for (SpeedKey k : tl.speed().all()) {
            boolean selected = selSpeed != null && selSpeed == k.id();
            boolean ghosted = mode == Mode.SPEED && selected;
            int x = sx(k.timeMs()), y = speedToY(k.speed());
            if (selected) ctx.fill(x - SPEED_R - 1, y - SPEED_R - 1, x + SPEED_R + 2, y + SPEED_R + 2, ghosted ? 0x55FFFFFF : 0xFFFFFFFF);
            ctx.fill(x - SPEED_R, y - SPEED_R, x + SPEED_R + 1, y + SPEED_R + 1, ghosted ? 0x66E8A33D : AMBER);
        }
        if (mode == Mode.SPEED) {
            int x = sx(speedTime), y = speedToY(speedVal);
            int c = speedDragValid ? 0xCCFFFFFF : 0xCCFF4D5E;
            ctx.fill(x - SPEED_R, y - SPEED_R, x + SPEED_R + 1, y + SPEED_R + 1, c);
        }
        ctx.disableScissor();

        if (tl.speed().isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Velocidade normal (1x). Duplo clique aqui para criar um ponto.",
                    (x0() + x1()) / 2, sy + SPEED_H / 2 - 4, DISABLED);
        }

        if (mode == Mode.NONE && inSpeed(mx, my)) {
            Optional<SpeedKey> h = hitSpeed(mx, my);
            if (h.isPresent()) {
                SpeedKey k = h.get();
                tip = List.of(
                        Text.literal(String.format(Locale.ROOT, "Velocidade %.2fx", k.speed())),
                        Text.literal("em " + TimelineView.format(k.timeMs())),
                        Text.literal("Arraste: mover | Botão direito: apagar"));
            } else {
                double v = snapSpeed(yToSpeed(my));
                tip = List.of(Text.literal(String.format(Locale.ROOT, "Duplo clique: novo ponto (%.2fx)", v)));
            }
        } else if (mode == Mode.SPEED) {
            tip = List.of(Text.literal(String.format(Locale.ROOT, "%.2fx em %s", speedVal, TimelineView.format(speedTime))));
        }
        return tip;
    }

    private List<Text> drawInspector(DrawContext ctx, int mx, int my, List<Text> tip) {
        int y = inspY();
        String info;
        boolean dim = false;
        if (selSpeed != null) {
            info = tl.speed().byId(selSpeed)
                    .map(k -> String.format(Locale.ROOT, "Ponto de velocidade: %.2fx em %s", k.speed(), TimelineView.format(k.timeMs())))
                    .orElse("");
        } else if (sel.isEmpty()) {
            info = "Selecione keyframes para editar a interpolação";
            dim = true;
        } else if (sel.ids().size() == 1) {
            long id = sel.ids().iterator().next();
            info = tl.path().byId(id).map(k -> "Keyframe em " + TimelineView.format(k.timeMs())).orElse("");
        } else {
            info = sel.ids().size() + " keyframes selecionados";
        }
        ctx.drawTextWithShadow(textRenderer, info, PAD, y + 5, dim ? DISABLED : TEXT);
        for (Btn b : buttons) if (b.group == 1) tip = drawButton(ctx, b, mx, my, tip);
        return tip;
    }

    private void drawPlayhead(DrawContext ctx) {
        int px = sx(dir.playheadMs());
        if (px < x0() || px > x1()) return;
        ctx.fill(px, rulerY(), px + 1, lanesBottom(), PLAYHEAD);
        ctx.fill(px - 4, rulerY(), px + 5, rulerY() + 5, PLAYHEAD);
    }

    private static void diamond(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int d = -r; d <= r; d++) {
            int w = r - Math.abs(d);
            ctx.fill(cx - w, cy + d, cx + w + 1, cy + d + 1, color);
        }
    }

    // ================= mouse =================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : buttons)
                if (hit(b, mx, my)) {
                    if (b.enabled.getAsBoolean()) b.action.run();
                    return true;
                }
        }

        boolean overRuler = inRuler(mx, my), overLane = inLane(mx, my), overSpeed = inSpeed(mx, my);
        if (!overRuler && !overLane && !overSpeed) return super.mouseClicked(mx, my, button);

        startX = curX = mx;
        startY = curY = my;
        shiftAtPress = hasShiftDown();

        if (button == 2) { mode = Mode.PAN; return true; }

        if (overRuler && button == 0) { mode = Mode.SCRUB; scrubTo(mx); return true; }

        if (overSpeed) return clickSpeed(mx, my, button);

        // ----- faixa da câmera -----
        Optional<Keyframe> hit = view.hit(tl.path(), mx - x0(), KEY_R + 3);

        if (button == 1) { // botão direito: troca a interpolação
            if (hit.isPresent()) {
                if (!sel.contains(hit.get().id())) sel.set(hit.get().id());
                selSpeed = null;
                Easing cur = selectionEasing().orElse(hit.get().easing());
                tl.setEasing(sel.ids(), cur.next());
            }
            return true;
        }

        if (button == 0) {
            if (hit.isPresent()) {
                Keyframe k = hit.get();
                selSpeed = null;
                if (shiftAtPress) {
                    sel.toggle(k.id());
                    mode = Mode.NONE;
                    return true;
                }
                if (!sel.contains(k.id())) sel.set(k.id());
                dir.pause();
                dir.setPlayhead(k.timeMs()); // clicar mostra aquela tomada
                anchorTimeMs = k.timeMs();
                grabOffsetMs = view.msAt(mx - x0()) - k.timeMs();
                dragDelta = 0;
                dragValid = true;
                mode = Mode.KEYS;
            } else {
                mode = Mode.BOX;
            }
        }
        return true;
    }

    private boolean clickSpeed(double mx, double my, int button) {
        Optional<SpeedKey> hit = hitSpeed(mx, my);

        if (button == 1) { // botão direito apaga
            hit.ifPresent(k -> { tl.removeSpeed(k.id()); selSpeed = null; });
            return true;
        }
        if (button != 0) return true;

        if (hit.isPresent()) {
            SpeedKey k = hit.get();
            selSpeed = k.id();
            sel.clear();
            dir.pause();
            dir.setPlayhead(k.timeMs());
            speedTime = k.timeMs();
            speedVal = k.speed();
            speedDragValid = true;
            speedGrabDx = mx - sx(k.timeMs());
            speedGrabDy = my - speedToY(k.speed());
            mode = Mode.SPEED;
            lastClickMs = 0;
            return true;
        }

        long now = System.currentTimeMillis();
        boolean dbl = now - lastClickMs < DOUBLE_CLICK_MS && Math.abs(mx - lastClickX) < 6 && Math.abs(my - lastClickY) < 6;
        if (dbl) { // duplo clique no vazio: cria ponto
            long t = view.snap(view.msAt(mx - x0()), snap);
            SpeedKey k = tl.addSpeed(t, snapSpeed(yToSpeed(my)));
            selSpeed = k.id();
            sel.clear();
            lastClickMs = 0;
            return true;
        }
        lastClickMs = now;
        lastClickX = mx;
        lastClickY = my;
        selSpeed = null;
        mode = Mode.SCRUB; // clique simples: move o playhead
        scrubTo(mx);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        curX = mx;
        curY = my;
        switch (mode) {
            case SCRUB -> scrubTo(mx);
            case PAN -> view.panPx(dx);
            case KEYS -> {
                long target = view.snap(view.msAt(mx - x0()) - grabOffsetMs, snap);
                long delta = tl.clampDelta(sel.ids(), target - anchorTimeMs);
                dragDelta = delta;
                dragValid = delta == 0 || tl.canMove(sel.ids(), delta);
            }
            case SPEED -> {
                long t = view.snap(view.msAt(mx - speedGrabDx - x0()), snap);
                speedDragValid = selSpeed != null && tl.canPlaceSpeed(selSpeed, t);
                if (speedDragValid) speedTime = t;
                speedVal = snapSpeed(yToSpeed(my - speedGrabDy));
            }
            case BOX, NONE -> { }
        }
        return mode != Mode.NONE || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        switch (mode) {
            case KEYS -> {
                if (dragDelta != 0 && dragValid) tl.move(sel.ids(), dragDelta);
                dragDelta = 0;
            }
            case SPEED -> {
                if (selSpeed != null) tl.editSpeed(selSpeed, speedTime, speedVal);
            }
            case BOX -> {
                if (Math.hypot(curX - startX, curY - startY) < 4) {
                    sel.clear();
                    selSpeed = null;
                    dir.pause();
                    dir.setPlayhead(view.msAt(mx - x0())); // clique simples no vazio: move o playhead
                } else {
                    List<Long> ids = new ArrayList<>();
                    for (Keyframe k : view.inRange(tl.path(), startX - x0(), curX - x0())) ids.add(k.id());
                    if (shiftAtPress) { Set<Long> merged = new LinkedHashSet<>(sel.ids()); merged.addAll(ids); sel.setAll(merged); }
                    else sel.setAll(ids);
                    if (!sel.isEmpty()) selSpeed = null;
                }
            }
            default -> { }
        }
        boolean was = mode != Mode.NONE;
        mode = Mode.NONE;
        return was || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!inTimelineX(mx) || my < rulerY() || my > lanesBottom()) return super.mouseScrolled(mx, my, amount);
        if (hasShiftDown()) view.panPx(amount * 48);
        else view.zoomAt(amount > 0 ? 1.2 : 1 / 1.2, mx - x0());
        return true;
    }

    // ================= teclado =================

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ctrl = hasControlDown(), shift = hasShiftDown();
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> dir.togglePlay();
            case GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> captureHere();
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                if (selSpeed != null) { tl.removeSpeed(selSpeed); selSpeed = null; }
                else { tl.remove(new HashSet<>(sel.ids())); sel.clear(); }
            }
            case GLFW.GLFW_KEY_Z -> { if (!ctrl) return super.keyPressed(key, scan, mods); if (shift) tl.redo(); else tl.undo(); }
            case GLFW.GLFW_KEY_Y -> { if (!ctrl) return super.keyPressed(key, scan, mods); tl.redo(); }
            case GLFW.GLFW_KEY_S -> { if (!ctrl) return super.keyPressed(key, scan, mods); save(shift); }
            case GLFW.GLFW_KEY_O -> { if (!ctrl) return super.keyPressed(key, scan, mods); openDialog(); }
            case GLFW.GLFW_KEY_N -> { if (!ctrl) return super.keyPressed(key, scan, mods); newPath(); }
            case GLFW.GLFW_KEY_A -> {
                if (!ctrl) return super.keyPressed(key, scan, mods);
                List<Long> ids = new ArrayList<>();
                for (Keyframe k : tl.path().all()) ids.add(k.id());
                sel.setAll(ids);
                selSpeed = null;
            }
            case GLFW.GLFW_KEY_HOME -> { dir.pause(); dir.setPlayhead(0); view.follow(0); }
            case GLFW.GLFW_KEY_END -> { dir.pause(); dir.setPlayhead(tl.path().lastTimeMs()); view.follow(dir.playheadMs()); }
            case GLFW.GLFW_KEY_LEFT -> { if (shift) { dir.pause(); dir.setPlayhead(dir.playheadMs() - 100); } else jumpToKeyframe(-1); }
            case GLFW.GLFW_KEY_RIGHT -> { if (shift) { dir.pause(); dir.setPlayhead(dir.playheadMs() + 100); } else jumpToKeyframe(1); }
            default -> { return super.keyPressed(key, scan, mods); }
        }
        return true;
    }
}
