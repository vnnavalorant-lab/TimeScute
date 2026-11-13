package dev.timescute.client;

import dev.timescute.core.ReplayClock;
import dev.timescute.core.ReplayLibrary;
import dev.timescute.core.ReplayMeta;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Painel de controle do replay: o mundo continua visível acima e o mouse fica livre para usar a barra. */
public final class ReplayControlScreen extends Screen {
    private static final int PANEL_H = 74, BAR_PAD = 64, BAR_H = 10;
    private static final int BG = 0xEE12151C, TRACK = 0xFF202636, ACCENT = 0xFF4DA3FF, AMBER = 0xFFE8A33D, TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6;
    private static final double[] SHOWN_SPEEDS = {0.25, 0.5, 1, 2, 4, 8};

    private final ReplaySession s = ReplaySession.get();
    private ButtonWidget bPlay;
    private final List<ButtonWidget> speedButtons = new ArrayList<>();
    private boolean dragging;
    private long dragMs;

    public ReplayControlScreen() {
        super(Text.literal("Controles do replay"));
    }

    @Override
    protected void init() {
        int y1 = height - PANEL_H + 6, y2 = height - PANEL_H + 28, x = 8;

        x = add("-10 s", x, y1, 50, () -> s.jump(-10_000));
        bPlay = addDrawableChild(ButtonWidget.builder(Text.literal("Pausar"), b -> s.togglePause()).dimensions(x, y1, 70, 20).build());
        x += 74;
        x = add("+10 s", x, y1, 50, () -> s.jump(10_000));
        x = add("< Marcador", x + 8, y1, 74, s::seekToPreviousMarker);
        add("Marcador >", x, y1, 74, s::seekToNextMarker);

        int sx = 8;
        speedButtons.clear();
        for (double sp : SHOWN_SPEEDS) {
            final double speed = sp;
            ButtonWidget b = addDrawableChild(ButtonWidget.builder(Text.literal(ReplaySession.speedLabel(sp)), btn -> s.setSpeed(speed))
                    .dimensions(sx, y2, 40, 20).build());
            speedButtons.add(b);
            sx += 44;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Sair do replay"), b -> s.exitReplay())
                .dimensions(width - 108, y2, 100, 20).build());
    }

    private int add(String label, int x, int y, int w, Runnable r) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> r.run()).dimensions(x, y, w, 20).build());
        return x + w + 4;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ----- barra de busca -----
    private int x0() { return BAR_PAD; }
    private int x1() { return width - BAR_PAD; }
    private int barY() { return height - 18; }

    private long msAt(double mx) {
        double f = (mx - x0()) / Math.max(1.0, x1() - x0());
        return Math.round(Math.max(0, Math.min(1, f)) * s.clock().durationMs());
    }

    private int xAt(long ms) {
        return x0() + (int) ((long) (x1() - x0()) * ms / Math.max(1, s.clock().durationMs()));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        if (!s.isActive()) { close(); return; }
        ReplayClock c = s.clock();
        ctx.fill(0, height - PANEL_H, width, height, BG);
        ctx.fill(0, height - PANEL_H, width, height - PANEL_H + 1, 0xFF2A3142);

        bPlay.setMessage(Text.literal(c.isPlaying() ? "Pausar" : "Play"));
        for (int i = 0; i < speedButtons.size(); i++) speedButtons.get(i).active = Math.abs(c.speed() - SHOWN_SPEEDS[i]) > 1e-9;

        String state = s.isLoading() ? "buscando..." : c.isPlaying() ? "reproduzindo" : "pausado";
        String info = state + "  |  " + ReplaySession.speedLabel(c.speed());
        ctx.drawTextWithShadow(textRenderer, info, width - 8 - textRenderer.getWidth(info), height - PANEL_H + 12, MUTED);

        // barra
        int by = barY();
        ctx.fill(x0(), by, x1(), by + BAR_H, TRACK);
        long shown = dragging ? dragMs : c.timeMs();
        ctx.fill(x0(), by, xAt(shown), by + BAR_H, ACCENT);
        ReplayMeta meta = s.meta();
        for (ReplayMeta.Marker m : meta.markerList) ctx.fill(xAt(m.timeMs()), by - 3, xAt(m.timeMs()) + 2, by + BAR_H + 3, AMBER);
        int tx = xAt(shown);
        ctx.fill(tx - 2, by - 4, tx + 3, by + BAR_H + 4, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, ReplayLibrary.formatDuration(shown), 8, by + 1, TEXT);
        String end = ReplayLibrary.formatDuration(c.durationMs());
        ctx.drawTextWithShadow(textRenderer, end, width - 8 - textRenderer.getWidth(end), by + 1, MUTED);

        super.render(ctx, mx, my, delta);

        if (mx >= x0() && mx <= x1() && my >= by - 6 && my <= by + BAR_H + 6) {
            String tip = ReplayLibrary.formatDuration(msAt(mx));
            for (ReplayMeta.Marker m : meta.markerList) if (Math.abs(xAt(m.timeMs()) - mx) <= 3) tip += "  " + m.text();
            ctx.drawTooltip(textRenderer, List.of(Text.literal(tip)), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && mx >= x0() - 4 && mx <= x1() + 4 && my >= barY() - 6 && my <= barY() + BAR_H + 6) {
            dragging = true;
            dragMs = msAt(mx);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) { dragMs = msAt(mx); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            dragging = false;
            s.seek(dragMs); // só busca ao soltar: voltar no tempo recomeça o replay e seria pesado a cada pixel
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean shift = hasShiftDown();
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> s.togglePause();
            case GLFW.GLFW_KEY_LEFT -> s.jump(shift ? -30_000 : -5_000);
            case GLFW.GLFW_KEY_RIGHT -> s.jump(shift ? 30_000 : 5_000);
            case GLFW.GLFW_KEY_LEFT_BRACKET -> s.stepSpeed(-1);
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> s.stepSpeed(1);
            default -> { return super.keyPressed(key, scan, mods); }
        }
        return true;
    }
}
