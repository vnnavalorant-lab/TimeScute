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
    private static final int H_MIN = 350, ROW_H = 22, TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, ERROR = 0xFFFF6B6B, ACCENT = 0xFF4DA3FF, WARN = 0xFFE8A33D;
    private static final int[] PALETTE = {0xFF4DA3FF, 0xFFE8A33D, 0xFF6BD68B, 0xFFE0626A, 0xFFB388FF, 0xFF26C6DA};

    private final Screen parent;
    private final CameraDirector dir = CameraDirector.get();
    private Long selected;
    private int scroll;
    private Long armed;
    private long armedUntil;
    private ButtonWidget bRecord, bMute, bDelete, bRename, bSkin, bModel, bHere;
    private java.util.List<String> modelMessages = java.util.List.of();

    public ActorsScreen(Screen parent) {
        super(Text.literal("Atores"));
        this.parent = parent;
        long last = ActorRecorder.get().lastActor();
        if (dir.timeline.actors().byId(last).isPresent()) selected = last;
        else if (!dir.timeline.actors().isEmpty()) selected = dir.timeline.actors().all().get(0).id();
    }

    private int W = 310;
    private boolean resizing;
    private int resizeW;

    private int px() { return UiLayout.dockX(width, W); }
    private int py() { return 10; }
    private int ph() { return Math.max(H_MIN, height - 20); }
    private int buttonsTop() { return py() + ph() - 152; }
    private int listBottom() { return buttonsTop() - 44; }
    private int visibleRows() { return Math.max(1, (listBottom() - (py() + 28)) / ROW_H); }

    @Override
    protected void init() {
        dir.setEditorOpen(true);
        W = UiLayout.dockW(width);
        modelMessages = ModelManager.get().reload();
        int x = px() + 10, bw = (W - 24) / 2, y = buttonsTop();
        add("Novo (gravar)", x, y, bw, this::newActor, "Cria um ator vazio. Depois grave a performance dele (Gravar).");
        add("Novo (roteiro)", x + bw + 4, y, bw, this::newScripted, "Cria um ator na SUA posição, sem gravar: você o dirige na timeline com blocos Andar até, Virar para, Animação...");
        add("<>", px() + W - 34, py() + 4, 24, this::flipSide, "Muda os painéis para o outro lado da tela (mais opções em Interface)");
        bRecord = add("Gravar", x, y + 24, bw, this::record, "Grava a SUA performance para este ator repetir (F8 para parar). Há uma contagem de 3 segundos.");
        bHere = add("Posição: aqui", x + bw + 4, y + 24, bw, this::moveHere, "Põe o ponto de partida do ator onde você está agora, virado para onde você olha (ator roteirizado).");
        bModel = add("Modelo", x, y + 48, bw, this::cycleModel, "Troca o jogador por um modelo do Blockbench (pasta config/timescute/models). Clique para passar ao próximo.");
        bSkin = add("Pele", x + bw + 4, y + 48, bw, this::skin, "Usa a pele de um jogador que esteja online. Vazio = a sua pele. (Só vale para o modelo de jogador.)");
        bRename = add("Renomear", x, y + 72, bw, this::rename, "");
        bMute = add("Ocultar", x + bw + 4, y + 72, bw, this::toggleMute, "Esconde o ator sem apagá-lo");
        bDelete = add("Excluir", x, y + 96, bw, this::delete, "");
        add("Animações...", x + bw + 4, y + 96, bw, () -> client.setScreen(new AnimationsScreen(this, null, null)), "Importa animações do Blockbench e calibra os eixos");
        add("Pasta de modelos", x, y + 120, bw, this::openModels, "Abre a pasta onde ficam os modelos (.geo.json + .png). Você também pode arrastar os arquivos para esta tela.");
        add("Voltar", x + bw + 4, y + 120, bw, this::close, "");
    }

    private ButtonWidget add(String label, int x, int y, int w, Runnable r, String tip) {
        ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), bt -> r.run()).dimensions(x, y, w, 20);
        if (!tip.isEmpty()) b.tooltip(Tooltip.of(Text.literal(tip)));
        return addDrawableChild(b.build());
    }

    private static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, Math.max(1, n - 1)) + "…"; }

    private Optional<Actor> sel() { return selected == null ? Optional.empty() : dir.timeline.actors().byId(selected); }

    private void newActor() {
        Actor a = dir.timeline.addActor("Ator " + (dir.timeline.actors().size() + 1));
        selected = a.id();
        ActorRecorder.get().setLastActor(a.id());
    }

    private void flipSide() {
        ModSettings.get().dockLeft = !ModSettings.get().dockLeft;
        ModSettings.save();
        clearChildren();
        init();
    }

    private void newScripted() {
        Actor.Origin o = playerOrigin();
        if (o == null) return;
        Actor a = dir.timeline.addScriptedActor("Ator " + (dir.timeline.actors().size() + 1), o);
        selected = a.id();
        ActorRecorder.get().setLastActor(a.id());
        ActorManager.get().markDirty();
    }

    private Actor.Origin playerOrigin() {
        if (client.player == null) return null;
        return new Actor.Origin(client.player.getX(), client.player.getY(), client.player.getZ(), client.player.getYaw());
    }

    private void moveHere() {
        Actor.Origin o = playerOrigin();
        if (o == null) return;
        sel().ifPresent(a -> dir.timeline.updateActor(a.withOrigin(o), "Posição inicial do ator"));
    }

    private void cycleModel() {
        sel().ifPresent(a -> {
            List<String> keys = ModelManager.get().keys();
            if (keys.isEmpty()) return;
            int i = keys.indexOf(a.model());
            String next = i < 0 && a.model().isEmpty() ? keys.get(0) : i + 1 < keys.size() ? keys.get(i + 1) : "";
            dir.timeline.updateActor(a.withModel(next), next.isEmpty() ? "Voltar ao modelo do jogador" : "Modelo do ator: " + next);
        });
    }

    private void openModels() {
        try { java.nio.file.Files.createDirectories(ModelManager.get().dir()); } catch (java.io.IOException ignored) { /* a pasta pode já existir */ }
        net.minecraft.util.Util.getOperatingSystem().open(ModelManager.get().dir().toFile());
    }

    @Override
    public void filesDragged(List<java.nio.file.Path> paths) {
        int n = 0;
        try {
            java.nio.file.Files.createDirectories(ModelManager.get().dir());
            for (java.nio.file.Path p : paths) {
                String fn = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!(fn.endsWith(".json") || fn.endsWith(".png"))) continue;
                java.nio.file.Files.copy(p, ModelManager.get().dir().resolve(p.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        } catch (java.io.IOException e) { modelMessages = List.of("Não foi possível copiar: " + e.getMessage()); return; }
        modelMessages = ModelManager.get().reload();
        if (n > 0) {
            List<String> all = new java.util.ArrayList<>(modelMessages);
            all.add(0, n + " arquivo(s) copiado(s) para a pasta de modelos.");
            modelMessages = all;
        }
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
        ctx.fill(x, y, x + W, y + ph(), UiLayout.bg());
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
            String st = a.hasPerformance() ? Fmt.duration(a.performance().durationMs()) + " | " + a.reactions().size() + " reação(ões)"
                    : a.isScripted() ? "roteirizado | " + a.reactions().size() + " bloco(s)" : "sem gravação";
            if (a.hasModel()) st += " | " + a.model();
            ctx.drawText(textRenderer, st, x + 24, ry + 12, a.hasPerformance() || a.isScripted() ? 0xFF565E70 : 0xFFB0644F, false);
        }

        Optional<Actor> a = sel();
        bRecord.setMessage(Text.literal(a.isPresent() && a.get().hasPerformance() ? "Regravar" : "Gravar"));
        bRecord.active = a.isPresent() && !ActorRecorder.get().isBusy();
        bRename.active = a.isPresent();
        bSkin.active = a.isPresent();
        bModel.active = a.isPresent() && !ModelManager.get().keys().isEmpty();
        bHere.active = a.isPresent() && (a.get().isScripted() || !a.get().hasPerformance());
        if (a.isPresent()) bModel.setMessage(Text.literal("Modelo: " + (a.get().hasModel() ? shorten(a.get().model(), 9) : "jogador")));
        bMute.active = a.isPresent();
        bMute.setMessage(Text.literal(a.isPresent() && a.get().muted() ? "Mostrar" : "Ocultar"));
        bDelete.active = a.isPresent();
        boolean armedNow = a.isPresent() && armed != null && armed == a.get().id() && System.currentTimeMillis() <= armedUntil;
        bDelete.setMessage(Text.literal(armedNow ? "Confirmar?" : "Excluir"));

        int ty = listBottom() + 4;
        if (a.isPresent()) {
            Actor ac = a.get();
            GeoModel gm = ac.hasModel() ? ModelManager.get().model(ac.model()) : null;
            if (ac.hasModel() && gm == null) {
                ctx.drawTextWithShadow(textRenderer, "Modelo \"" + ac.model() + "\" não encontrado: usa o jogador.", x + 10, ty, ERROR);
            } else if (gm != null) {
                ctx.drawTextWithShadow(textRenderer, "Modelo: " + ac.model() + " (" + gm.bones().size() + " ossos)", x + 10, ty, TEXT);
                boolean walks = gm.looksHumanoid() || ModelManager.get().clipFor(ac.model(), "walk") != null;
                ctx.drawText(textRenderer, walks ? "Anda e olha sozinho; use blocos Animação para gestos." : "Sem ossos de jogador: importe uma animação \"walk\" (e \"idle\") para ele andar.",
                        x + 10, ty + 11, walks ? 0xFF565E70 : WARN, false);
            } else {
                ctx.drawTextWithShadow(textRenderer, "Pele: " + (ac.skin().isEmpty() ? "a minha" : ac.skin()), x + 10, ty, TEXT);
                ctx.drawText(textRenderer, "A pele de outro jogador só aparece se ele estiver online.", x + 10, ty + 11, 0xFF565E70, false);
            }
            if (ac.isScripted() && !ac.hasPerformance())
                ctx.drawText(textRenderer, String.format(java.util.Locale.ROOT, "Começa em x %.1f  y %.1f  z %.1f", ac.origin().x(), ac.origin().y(), ac.origin().z()), x + 10, ty + 22, 0xFF565E70, false);
        }
        String note = modelMessages.isEmpty() ? "Os atores só existem no seu mundo (o servidor não os vê)." : modelMessages.get(0);
        ctx.drawText(textRenderer, shorten(note, 52), x + 10, ty + 33, modelMessages.isEmpty() ? 0xFF565E70 : WARN, false);
        super.render(ctx, mx, my, delta);
        UiLayout.drawHandle(ctx, px(), py(), W, ph(), resizing || UiLayout.overHandle(mx, my, px(), py(), W, ph()));
        if (resizing) UiLayout.drawGhost(ctx, mx, height, resizeW, textRenderer);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && UiLayout.overHandle(mx, my, px(), py(), W, ph())) { resizing = true; resizeW = W; return true; }
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
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (resizing) { resizeW = UiLayout.widthAt(mx, width); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (resizing) {
            resizing = false;
            UiLayout.commitWidth(resizeW);
            clearChildren();
            init();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void removed() { dir.setEditorOpen(false); }

    @Override
    public void close() { client.setScreen(parent); }
}
