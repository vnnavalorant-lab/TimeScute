package dev.timescute.client;

import dev.timescute.core.Settings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Tamanhos da interface de edição. A timeline aparece atrás e muda ao vivo enquanto você mexe nos controles. */
public final class UiSettingsScreen extends Screen {
    private static final int W = 270, H = 184, TEXT = 0xFFE6EAF2;
    private final Screen parent;

    public UiSettingsScreen(Screen parent) {
        super(Text.literal("Interface"));
        this.parent = parent;
    }

    private Settings s() { return ModSettings.get(); }

    /** O painel fica no canto de cima, do lado oposto ao dos painéis, para não cobrir a timeline que está mudando. */
    private int px() { return s().dockLeft ? width - W - 10 : 10; }
    private int py() { return 10; }

    private static final class Slider extends SliderWidget {
        private final String label, unit;
        private final int min, max;
        private final IntConsumer onChange;

        Slider(int x, int y, int w, String label, String unit, int min, int max, int value, IntConsumer onChange) {
            super(x, y, w, 20, Text.empty(), (value - min) / (double) (max - min));
            this.label = label; this.unit = unit; this.min = min; this.max = max; this.onChange = onChange;
            updateMessage();
        }

        private int current() { return min + (int) Math.round(value * (max - min)); }

        @Override protected void updateMessage() { setMessage(Text.literal(label + ": " + current() + unit)); }
        @Override protected void applyValue() { onChange.accept(current()); }
    }

    @Override
    protected void init() {
        int x = px() + 10, y = py() + 24, w = W - 20;
        addDrawableChild(new Slider(x, y, w, "Largura dos painéis", " px", Settings.DOCK_MIN, Settings.DOCK_MAX, s().dockWidth, v -> s().dockWidth = v));
        addDrawableChild(new Slider(x, y + 24, w, "Faixas de atores", "", Settings.LANES_MIN, Settings.LANES_MAX, s().lanesVisible, v -> s().lanesVisible = v));
        addDrawableChild(new Slider(x, y + 48, w, "Altura da faixa da câmera", " px", Settings.CAM_LANE_MIN, Settings.CAM_LANE_MAX, s().camLaneHeight, v -> s().camLaneHeight = v));
        addDrawableChild(new Slider(x, y + 72, w, "Opacidade dos painéis", "%", Settings.OPACITY_MIN, Settings.OPACITY_MAX, s().panelOpacity, v -> s().panelOpacity = v));
        int bw = (w - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal(side()), b -> { s().dockLeft = !s().dockLeft; clearChildren(); init(); })
                .dimensions(x, y + 96, bw, 20).tooltip(Tooltip.of(Text.literal("Põe os painéis de Atores e Reação do outro lado da tela"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(grid()), b -> { s().showGrid = !s().showGrid; b.setMessage(Text.literal(grid())); })
                .dimensions(x + bw + 4, y + 96, bw, 20).tooltip(Tooltip.of(Text.literal("Terços, centro e área segura sobre a cena. Só uma guia: não aparece no vídeo."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Restaurar padrão"), b -> {
            Settings d = new Settings();
            s().dockWidth = d.dockWidth; s().lanesVisible = d.lanesVisible; s().camLaneHeight = d.camLaneHeight;
            s().panelOpacity = d.panelOpacity; s().dockLeft = d.dockLeft; s().showGrid = d.showGrid;
            clearChildren(); init();
        }).dimensions(x, y + 120, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pronto"), b -> close()).dimensions(x + bw + 4, y + 120, bw, 20).build());
    }

    private String side() { return "Painéis: " + (s().dockLeft ? "esquerda" : "direita"); }
    private String grid() { return "Grade: " + (s().showGrid ? "ligada" : "desligada"); }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // a timeline continua desenhada atrás, já com os tamanhos novos
        if (parent != null) parent.render(ctx, -1, -1, delta);
        int x = px(), y = py();
        ctx.fill(x, y, x + W, y + H, 0xF012151C);
        ctx.drawBorder(x, y, W, H, 0xFF2A3142);
        ctx.drawCenteredTextWithShadow(textRenderer, "Tamanhos da interface", x + W / 2, y + 8, TEXT);
        ctx.drawText(textRenderer, "Dica: arraste a borda de um painel para mudar a largura.", x + 10, y + H - 16, 0xFF565E70, false);
        super.render(ctx, mx, my, delta);
    }

    @Override
    public void removed() {
        s().clamp();
        ModSettings.save();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
