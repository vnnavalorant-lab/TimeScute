package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Biblioteca de animações: importar do Blockbench, ver os ossos que cada uma usa e calibrar os eixos. */
public final class AnimationsScreen extends Screen {
    private static final int ROW_H = 18, TOP = 52, TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, WARN = 0xFFE8A33D, ERROR = 0xFFFF6B6B, OK = 0xFF6BD68B;

    private final Screen parent;
    private final Consumer<String> picker; // null = modo de gerenciamento
    private final ActorManager am = ActorManager.get();
    private List<String> names = new ArrayList<>();
    private List<String> messages = new ArrayList<>();
    private String selected;
    private int scroll;
    private long lastClickMs;
    private String lastClickName = "";
    private ButtonWidget bConv, bX, bY, bZ, bUse;

    public AnimationsScreen(Screen parent, String preselect, Consumer<String> picker) {
        super(Text.literal("Animações"));
        this.parent = parent;
        this.picker = picker;
        this.selected = preselect == null || preselect.isEmpty() ? null : preselect;
    }

    @Override
    protected void init() {
        CameraDirector.get().setEditorOpen(true);
        names = am.clips().names();
        int bw = Math.max(80, Math.min(130, (width - 40) / 5)), y1 = height - 52, y2 = height - 28, x = 10;
        addDrawableChild(ButtonWidget.builder(Text.literal("Reler pasta"), b -> reload())
                .dimensions(x, y1, bw, 20).tooltip(Tooltip.of(Text.literal("Lê de novo os arquivos .json da pasta de animações"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Abrir pasta"), b -> openFolder()).dimensions(x + bw + 4, y1, bw, 20).build());
        bConv = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> editConfig(0)).dimensions(x + 2 * (bw + 4), y1, bw + 40, 20)
                .tooltip(Tooltip.of(Text.literal("Como os eixos do arquivo viram os do jogo. Bedrock é o padrão do Blockbench para add-ons."))).build());
        bX = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> editConfig(1)).dimensions(x, y2, bw, 20).build());
        bY = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> editConfig(2)).dimensions(x + bw + 4, y2, bw, 20).build());
        bZ = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> editConfig(3)).dimensions(x + 2 * (bw + 4), y2, bw, 20).build());
        if (picker != null) bUse = addDrawableChild(ButtonWidget.builder(Text.literal("Usar esta animação"), b -> choose())
                .dimensions(width - 10 - 2 * (bw + 20) - 4, y2, bw + 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Voltar"), b -> close()).dimensions(width - 10 - (bw + 20), y2, bw + 20, 20).build());
        if (messages.isEmpty()) messages = new ArrayList<>(am.reloadAnimations());
        names = am.clips().names();
    }

    @Override
    public void removed() { CameraDirector.get().setEditorOpen(false); }

    @Override
    public void close() { client.setScreen(parent); }

    private void reload() {
        messages = new ArrayList<>(am.reloadAnimations());
        names = am.clips().names();
        if (selected != null && am.clips().clip(selected).isEmpty()) selected = null;
        if (messages.isEmpty()) messages.add("Pasta lida: " + am.clips().importedCount() + " animação(ões) importada(s).");
    }

    private void openFolder() {
        try {
            Files.createDirectories(am.animationsDir());
            Util.getOperatingSystem().open(am.animationsDir().toFile());
        } catch (IOException e) {
            messages = new ArrayList<>(List.of("Não foi possível abrir a pasta: " + e.getMessage()));
        }
    }

    /** Arrastar arquivos do Blockbench para a janela copia para a pasta de animações. */
    @Override
    public void filesDragged(List<Path> paths) {
        int copied = 0;
        List<String> errs = new ArrayList<>();
        try { Files.createDirectories(am.animationsDir()); } catch (IOException e) { errs.add(e.getMessage()); }
        for (Path p : paths) {
            if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) { errs.add(p.getFileName() + ": só arquivos .json"); continue; }
            try { Files.copy(p, am.animationsDir().resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING); copied++; }
            catch (IOException e) { errs.add(p.getFileName() + ": " + e.getMessage()); }
        }
        reload();
        messages.add(0, copied + " arquivo(s) copiado(s) para a pasta de animações.");
        messages.addAll(1, errs);
    }

    /** which: 0 = convenção, 1/2/3 = inverter giro X/Y/Z */
    private void editConfig(int which) {
        if (selected == null) return;
        AnimLibrary.Config c = am.clips().config(selected);
        AnimLibrary.Config n = switch (which) {
            case 0 -> new AnimLibrary.Config(c.convention().next(), c.flipPitch(), c.flipYaw(), c.flipRoll());
            case 1 -> new AnimLibrary.Config(c.convention(), !c.flipPitch(), c.flipYaw(), c.flipRoll());
            case 2 -> new AnimLibrary.Config(c.convention(), c.flipPitch(), !c.flipYaw(), c.flipRoll());
            default -> new AnimLibrary.Config(c.convention(), c.flipPitch(), c.flipYaw(), !c.flipRoll());
        };
        am.clips().setConfig(selected, n);
        am.saveClipConfig();
    }

    private void choose() {
        if (selected == null || picker == null) return;
        picker.accept(selected);
        client.setScreen(parent);
    }

    private int listBottom() { return height - 130; }
    private int visibleRows() { return Math.max(1, (listBottom() - TOP) / ROW_H); }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0xDD0E1118);
        ctx.drawCenteredTextWithShadow(textRenderer, picker != null ? "Escolher animação" : "Animações", width / 2, 8, TEXT);
        ctx.drawCenteredTextWithShadow(textRenderer, "Arraste aqui os arquivos .animation.json do Blockbench (ou coloque-os na pasta e clique em Reler).", width / 2, 22, MUTED);
        ctx.drawCenteredTextWithShadow(textRenderer, "Funciona com o formato Bedrock. Partes do jogador: cabeça, corpo, braços e pernas (nomes de ossos como head, body, rightArm...).", width / 2, 34, MUTED);

        for (int i = 0; i < visibleRows() && i + scroll < names.size(); i++) {
            String n = names.get(i + scroll);
            AnimClip c = am.clips().clip(n).orElse(null);
            int y = TOP + i * ROW_H;
            boolean sel = n.equals(selected), hov = mx >= 10 && mx < width - 10 && my >= y && my < y + ROW_H - 1;
            ctx.fill(10, y, width - 10, y + ROW_H - 1, sel ? 0xFF1F3F66 : hov ? 0xFF2B3345 : 0xFF181C26);
            String tag = c == null ? "" : c.builtin() ? "  (embutida)" : "";
            ctx.drawTextWithShadow(textRenderer, ClipLibrary.label(n) + tag, 16, y + 5, c != null && !c.affectsPlayer() ? ERROR : TEXT);
            if (c != null) {
                String info = String.format(Locale.ROOT, "%.1f s  |  %s", c.length(), switch (c.loop()) { case LOOP -> "repete"; case HOLD -> "segura o fim"; case ONCE -> "uma vez"; });
                if (!c.unmappedBones().isEmpty()) info += "  |  ossos ignorados: " + c.unmappedBones().size();
                ctx.drawTextWithShadow(textRenderer, info, width - 16 - textRenderer.getWidth(info), y + 5, c.unmappedBones().isEmpty() ? MUTED : WARN);
            }
        }
        if (names.size() > visibleRows()) ctx.drawCenteredTextWithShadow(textRenderer, "role para ver mais", width / 2, listBottom() + 1, 0xFF565E70);

        // detalhes do selecionado
        int dy = listBottom() + 12;
        AnimClip sc = selected == null ? null : am.clips().clip(selected).orElse(null);
        if (sc == null) {
            ctx.drawTextWithShadow(textRenderer, "Selecione uma animação para ver os detalhes e calibrar os eixos.", 12, dy, MUTED);
        } else {
            List<String> mapped = new ArrayList<>();
            for (String b : sc.bones().keySet()) { Part p = Part.fromBone(b); if (p != null) mapped.add(p.label); }
            ctx.drawTextWithShadow(textRenderer, "Controla: " + (mapped.isEmpty() ? "nada (nenhum osso é do jogador)" : String.join(", ", mapped)), 12, dy, mapped.isEmpty() ? ERROR : OK);
            if (!sc.unmappedBones().isEmpty())
                ctx.drawTextWithShadow(textRenderer, "Ignorados (o jogador não tem esses ossos): " + String.join(", ", sc.unmappedBones()), 12, dy + 11, WARN);
            if (selected.equals(BuiltinClips.CALIBRATE)) {
                int hy = dy + 11 + (sc.unmappedBones().isEmpty() ? 0 : 11);
                int maxLines = Math.max(1, (listBottom() + 56 - hy) / 11);
                var wrapped = textRenderer.wrapLines(Text.literal("Como calibrar: use esta animação em um ator e confira. " + BuiltinClips.CALIBRATE_HELP), width - 24);
                for (int i = 0; i < Math.min(maxLines, wrapped.size()); i++) ctx.drawTextWithShadow(textRenderer, wrapped.get(i), 12, hy + i * 11, i == 0 ? TEXT : MUTED);
            }
        }
        int my0 = listBottom() + 56 > height - 62 ? height - 62 : listBottom() + 56;
        for (int i = 0; i < Math.min(2, messages.size()); i++) {
            String m = messages.get(i);
            while (textRenderer.getWidth(m) > width - 24 && m.length() > 10) m = m.substring(0, m.length() - 4) + "...";
            ctx.drawTextWithShadow(textRenderer, m, 12, my0 + i * 11, i == 0 && m.contains("Pasta lida") ? OK : WARN);
        }

        AnimLibrary.Config cfg = selected == null ? null : am.clips().config(selected);
        bConv.active = cfg != null; bX.active = cfg != null; bY.active = cfg != null; bZ.active = cfg != null;
        bConv.setMessage(Text.literal("Eixos: " + (cfg == null ? "-" : cfg.convention().label)));
        bX.setMessage(Text.literal("Inverter X: " + (cfg != null && cfg.flipPitch() ? "sim" : "não")));
        bY.setMessage(Text.literal("Inverter Y: " + (cfg != null && cfg.flipYaw() ? "sim" : "não")));
        bZ.setMessage(Text.literal("Inverter Z: " + (cfg != null && cfg.flipRoll() ? "sim" : "não")));
        if (bUse != null) bUse.active = sc != null && sc.affectsPlayer();
        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && my >= TOP && my < listBottom() && mx >= 10 && mx < width - 10) {
            int idx = (int) ((my - TOP) / ROW_H) + scroll;
            if (idx >= 0 && idx < names.size()) {
                String n = names.get(idx);
                long now = System.currentTimeMillis();
                if (n.equals(lastClickName) && now - lastClickMs < 350 && picker != null) { selected = n; choose(); return true; }
                selected = n;
                lastClickName = n;
                lastClickMs = now;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scroll = Math.max(0, Math.min(Math.max(0, names.size() - visibleRows()), scroll - (int) Math.signum(amount)));
        return true;
    }
}
