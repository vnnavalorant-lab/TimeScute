package dev.timescute.client;

import dev.timescute.core.Settings;
import net.minecraft.client.gui.DrawContext;

/** Tamanhos e posições da interface de edição (painéis laterais, opacidade), vindos de Configurações → Interface. */
final class UiLayout {
    private UiLayout() { }

    static final int MARGIN = 8, HANDLE_PX = 4;

    private static Settings s() { return ModSettings.get(); }

    /** Largura do painel lateral: a configurada, sem passar de metade da tela. */
    static int dockW(int screenW) {
        return Math.max(Settings.DOCK_MIN, Math.min(s().dockWidth, Math.max(Settings.DOCK_MIN, screenW / 2)));
    }

    static int dockX(int screenW, int w) { return s().dockLeft ? MARGIN : screenW - w - MARGIN; }

    /** Cor de fundo dos painéis com a opacidade escolhida (para ver a cena através deles). */
    static int bg() { return ((s().panelOpacity * 255 / 100) << 24) | 0x12151C; }

    // ---- alça de redimensionar, na borda do painel voltada para a cena ----

    static boolean overHandle(double mx, double my, int x, int y, int w, int h) {
        int edge = s().dockLeft ? x + w : x;
        return Math.abs(mx - edge) <= HANDLE_PX && my >= y && my < y + h;
    }

    /** Largura que o painel teria se a borda fosse arrastada até o mouse. */
    static int widthAt(double mx, int screenW) {
        int w = (int) Math.round(s().dockLeft ? mx - MARGIN : screenW - MARGIN - mx);
        return Math.max(Settings.DOCK_MIN, Math.min(Settings.DOCK_MAX, Math.min(w, screenW / 2)));
    }

    static void commitWidth(int w) {
        s().dockWidth = w;
        s().clamp();
        ModSettings.save();
    }

    static void drawHandle(DrawContext ctx, int x, int y, int w, int h, boolean hot) {
        int edge = s().dockLeft ? x + w - 1 : x;
        ctx.fill(edge - 1, y + h / 2 - 14, edge + 2, y + h / 2 + 14, hot ? 0xFF4DA3FF : 0xFF3A4358);
    }

    /** Linha de prévia enquanto a borda é arrastada. */
    static void drawGhost(DrawContext ctx, int mouseX, int screenH, int newW, net.minecraft.client.font.TextRenderer font) {
        ctx.fill(mouseX - 1, 0, mouseX + 1, screenH, 0xAA4DA3FF);
        ctx.drawTextWithShadow(font, newW + " px", mouseX + 6, 10, 0xFFE6EAF2);
    }

    /** Grade de enquadramento: terços, centro e área segura (90%). Só uma guia: não aparece no vídeo. */
    static void drawFrameGrid(DrawContext ctx, int w, int h) {
        int c = 0x66FFFFFF;
        ctx.fill(w / 3, 0, w / 3 + 1, h, c);
        ctx.fill(2 * w / 3, 0, 2 * w / 3 + 1, h, c);
        ctx.fill(0, h / 3, w, h / 3 + 1, c);
        ctx.fill(0, 2 * h / 3, w, 2 * h / 3 + 1, c);
        ctx.fill(w / 2 - 6, h / 2, w / 2 + 7, h / 2 + 1, c);
        ctx.fill(w / 2, h / 2 - 6, w / 2 + 1, h / 2 + 7, c);
        ctx.drawBorder(w / 20, h / 20, w - w / 10, h - h / 10, 0x44FFFFFF);
    }
}
