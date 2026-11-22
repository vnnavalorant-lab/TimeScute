package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Optional;

/** Lista dos atores da cena: criar, renomear, trocar a pele, ocultar, excluir e gravar. */
public final class ActorsScreen extends Screen {
    private static final int W = 310, H_MIN = 300, ROW_H = 22, TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, ERROR = 0xFFFF6B6B, ACCENT = 0xFF4DA3FF;
    private static final int[] PALETTE = {0xFF4DA3FF, 0xFFE8A33D, 0xFF6BD68B, 0xFFE0626A, 0xFFB388FF, 0xFF26C6DA};

    private final Screen parent;
    private final CameraDirector dir = CameraDirector.get();
    private Long selected;
    private int scroll;
    private Long armed;
    private long armedUntil;
    private ButtonWidget bRecord, bMute, bDelete, bRename, bSkin;

    public ActorsScreen(Screen parent) {
        super(Text.literal("Atores"));
        this.parent = parent;
        long last = ActorRecorder.get().lastActor();
        if (dir.timeline.actors().byId(last).isPresent()) selected = last;
        else if (!dir.timeline.actors().isEmpty()) selected = dir.timeline.actors().all().get(0).id();
    }

    private int px() { return width - W - 10; }
    private int py() { return 10; }
    private int ph() { return Math.max(H_MIN, height - 20); }
    private int listBottom() { return py() + ph() - 132; }
    private int visibleRows() { return Math.max(1, (listBottom() - (py() + 28)) / ROW_H); }

    @Override
    protected void init() {
        dir.setEditorOpen(true);
        int x = px() + 10, bw = (W - 24) / 2, y = py() + ph() - 124;
        add("Novo ator", x, y, bw, this::newActor, "Cria um ator vazio. Depois grave a performance dele.");
        bRecord = add("Gravar", x + bw + 4, y, bw, this::record, "Grava a SUA performance para este ator repetir (F8 para parar). Há uma contagem de 3 segundos.");
        bRename = add("Renomear", x, y + 24, bw, this::rename, "");
        bSkin = add("Pele", x + bw + 4, y + 24, bw, this::skin, "Usa a pele de um jogador que esteja online. Vazio = a sua pele.");
        bMute = add("Ocultar", x, y + 48, bw, this::toggleMute, "Esconde o ator sem apagá-lo");
        bDelete = add("Excluir", x + bw + 4, y + 48, bw, this::delete, "");
        add("Animações...", x, y + 76, bw, () -> client.setScreen(new AnimationsScreen(this, null, null)), "Importa animações do Blockbench e calibra os eixos");
        add("Voltar", x + bw + 4, y + 76, bw, this::close, "");
    }

    private ButtonWidget add(String label, int x, int y, int w, Runnable r, String tip) {
        ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), bt -> r.run()).dimensions(x, y, w, 20);
        if (!tip.isEmpty()) b.tooltip(Tooltip.of(Text.literal(tip)));
        return addDrawableChild(b.build());
    }

    private Optional<Actor> sel() { return selected == null ? Optional.empty() : dir.timeline.actors().byId(selected); }

    private void newActor() {
        Actor a = dir.timeline.addActor("Ator " + (dir.timeline.actors().size() + 1));
        selected = a.id();
        ActorRecorder.get().setLastActor(a.id());
    }

    private void record() {
        sel().ifPresent(a -> ActorRecorder.get().start(client, a.id()));
    }

    private void rename() {
        sel().ifPresent(a -> client.setScreen(new PromptScreen(this, "Renomear ator", a.name(), text -> {
            if (text.isBlank()) return "O nome não pode ficar vazio.";
            dir.timeline.updateActor(dir.timeline.actors().byId(a.id()).orElse(a).withName(text.trim()), "Renomear ator");
            return null;
        })));
    }

    private void skin() {
        sel().ifPresent(a -> client.setScreen(new PromptScreen(this, "Pele: nome de um jogador online (vazio = a sua)", a.skin(), text -> {
            dir.timeline.updateActor(dir.timeline.actors().byId(a.id()).orElse(a).withSkin(text.trim()), "Trocar pele");
            return null;
        })));
    }

    private void toggleMute() {
        sel().ifPresent(a -> dir.timeline.updateActor(a.withMuted(!a.muted()), a.muted() ? "Mostrar ator" : "Ocultar ator"));
    }

    private void delete() {
        Optional<Actor> a = sel();
        if (a.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (armed == null || armed != a.get().id() || now > armedUntil) { armed = a.get().id(); armedUntil = now + 4000; return; }
        armed = null;
        dir.timeline.removeActor(a.get().id());
        List<Actor> rest = dir.timeline.actors().all();
        selected = rest.isEmpty() ? null : rest.get(Math.min(rest.size() - 1, 0)).id();
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int x = px(), y = py();
        ctx.fill(x, y, x + W, y + ph(), 0xEE12151C);
        ctx.drawBorder(x, y, W, ph(), 0xFF2A3142);
        ctx.drawCenteredTextWithShadow(textRenderer, "Atores da cena", x + W / 2, y + 8, TEXT);

        List<Actor> as = dir.timeline.actors().all();
        if (as.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, "Nenhum ator. Clique em Novo ator.", x + W / 2, y + 44, MUTED);
        for (int i = 0; i < visibleRows() && i + scroll < as.size(); i++) {
            Actor a = as.get(i + scroll);
            int ry = y + 28 + i * ROW_H;
            boolean s = selected != null && selected == a.id(), hov = mx >= x + 8 && mx < x + W - 8 && my >= ry && my < ry + ROW_H - 2;
            ctx.fill(x + 8, ry, x + W - 8, ry + ROW_H - 2, s ? 0xFF1F3F66 : hov ? 0xFF2B3345 : 0xFF181C26);
            ctx.fill(x + 12, ry + 6, x + 18, ry + 12, PALETTE[(i + scroll) % PALETTE.length]);
            ctx.drawTextWithShadow(textRenderer, a.name() + (a.muted() ? " (oculto)" : ""), x + 24, ry + 3, s ? TEXT : MUTED);
            String st = a.hasPerformance() ? Fmt.duration(a.performance().durationMs()) + " | " + a.reactions().size() + " reação(ões)" : "sem gravação";
            ctx.drawText(textRenderer, st, x + 24, ry + 12, a.hasPerformance() ? 0xFF565E70 : 0xFFB0644F, false);
        }

        Optional<Actor> a = sel();
        bRecord.setMessage(Text.literal(a.isPresent() && a.get().hasPerformance() ? "Regravar" : "Gravar"));
        bRecord.active = a.isPresent() && !ActorRecorder.get().isBusy();
        bRename.active = a.isPresent();
        bSkin.active = a.isPresent();
        bMute.active = a.isPresent();
        bMute.setMessage(Text.literal(a.isPresent() && a.get().muted() ? "Mostrar" : "Ocultar"));
        bDelete.active = a.isPresent();
        boolean armedNow = a.isPresent() && armed != null && armed == a.get().id() && System.currentTimeMillis() <= armedUntil;
        bDelete.setMessage(Text.literal(armedNow ? "Confirmar?" : "Excluir"));

        int ty = listBottom() + 6;
        if (a.isPresent()) {
            ctx.drawTextWithShadow(textRenderer, "Pele: " + (a.get().skin().isEmpty() ? "a minha" : a.get().skin()), x + 10, ty, TEXT);
            ctx.drawText(textRenderer, "A pele de outro jogador só aparece se ele estiver online.", x + 10, ty + 11, 0xFF565E70, false);
        }
        ctx.drawText(textRenderer, "Os atores só existem no seu mundo (o servidor não os vê).", x + 10, ty + 22, 0xFF565E70, false);
        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int x = px(), y = py();
        if (button == 0 && mx >= x + 8 && mx < x + W - 8 && my >= y + 28 && my < listBottom()) {
            int idx = (int) ((my - (y + 28)) / ROW_H) + scroll;
            List<Actor> as = dir.timeline.actors().all();
            if (idx >= 0 && idx < as.size()) { selected = as.get(idx).id(); ActorRecorder.get().setLastActor(selected); armed = null; }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scroll = Math.max(0, Math.min(Math.max(0, dir.timeline.actors().size() - visibleRows()), scroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    public void removed() { dir.setEditorOpen(false); }

    @Override
    public void close() { client.setScreen(parent); }
}
