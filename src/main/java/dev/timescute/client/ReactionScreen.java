package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Optional;

/** Edição de uma reação: o painel fica à direita para você continuar vendo o ator e o mundo. */
public final class ReactionScreen extends Screen {
    private static final int H = 262;
    private int W = 330;
    private boolean resizing;
    private int resizeW;
    private static final int TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, ERROR = 0xFFFF6B6B, OK = 0xFF6BD68B, WARN = 0xFFE8A33D;
    private static final double[] SPEEDS = {0.5, 1, 1.5, 2};
    private static final double[] WEIGHTS = {0.25, 0.5, 0.75, 1};

    private final Screen parent;
    private final long actorId;
    private Reaction draft;
    private String error = "";
    private TextFieldWidget fStart, fDur, fItem;

    public ReactionScreen(Screen parent, long actorId, long reactionId) {
        super(Text.literal("Reação"));
        this.parent = parent;
        this.actorId = actorId;
        Optional<Reaction> r = CameraDirector.get().timeline.actors().byId(actorId).flatMap(a -> a.reaction(reactionId));
        this.draft = r.orElse(null);
        if (draft != null) { // mostra o ator no meio do bloco, para ver o efeito
            CameraDirector.get().pause();
            CameraDirector.get().setPlayhead(draft.startMs() + draft.durationMs() / 2);
        }
    }

    private ReactionScreen(Screen parent, long actorId, Reaction draft) {
        super(Text.literal("Reação"));
        this.parent = parent;
        this.actorId = actorId;
        this.draft = draft;
    }

    private int px() { return UiLayout.dockX(width, W); }
    private int py() { return Math.max(4, (height - H) / 2); }

    @Override
    protected void init() {
        if (draft == null) { close(); return; }
        CameraDirector.get().setEditorOpen(true);
        W = Math.max(320, UiLayout.dockW(width)); // os botões desta tela precisam de pelo menos 320
        int x = px(), y = py();

        btn("Tipo: " + draft.type().label, x + 10, y + 24, W - 20, () -> {
            commit();
            ReactionType t = draft.type().next(isScripted());
            draft = draft.withType(t);
            if (t.scriptedOnly() && draft.tx() == 0 && draft.ty() == 0 && draft.tz() == 0) useMyPosition();
            if (t == ReactionType.ANIMATION && draft.anim().clip().isEmpty()) draft = draft.withAnim(draft.anim().withClip(BuiltinClips.WAVE));
            if (t == ReactionType.SWAP_ITEM && draft.itemId().isEmpty()) draft = draft.withItem("minecraft:diamond_sword");
            reopen();
        }, draft.type().description);

        fStart = field(x + 10, y + 66, (W - 30) / 2, String.format(Locale.ROOT, "%.2f", draft.startMs() / 1000.0));
        fDur = field(x + 20 + (W - 30) / 2, y + 66, (W - 30) / 2, String.format(Locale.ROOT, "%.2f", draft.durationMs() / 1000.0));

        int yy = y + 94;
        switch (draft.type()) {
            case ANIMATION -> {
                AnimParams p = draft.anim();
                btn("Animação: " + ClipLibrary.label(p.clip()), x + 10, yy, W - 20, () -> {
                    commit();
                    client.setScreen(new AnimationsScreen(this, draft.anim().clip(), name -> draft = draft.withAnim(draft.anim().withClip(name))));
                }, "Escolhe uma animação embutida ou importada do Blockbench");
                int bw = (W - 28) / 3;
                for (int i = 0; i < Part.values().length; i++) {
                    Part part = Part.values()[i];
                    boolean on = Part.has(p.partMask(), part);
                    btn((on ? "[X] " : "[ ] ") + part.label, x + 10 + (i % 3) * (bw + 4), yy + 24 + (i / 3) * 22, bw, () -> { commit(); draft = draft.withAnim(draft.anim().toggled(part)); reopen(); },
                            "Marque as partes do corpo que esta animação controla. As outras seguem a sua performance.");
                }
                btn("Espelhar: " + (p.mirror() ? "sim" : "não"), x + 10, yy + 70, bw, () -> { commit(); draft = draft.withAnim(p.withMirror(!p.mirror())); reopen(); }, "Troca esquerda e direita");
                btn("Somar: " + (p.additive() ? "sim" : "não"), x + 14 + bw, yy + 70, bw, () -> { commit(); draft = draft.withAnim(p.withAdditive(!p.additive())); reopen(); },
                        "Soma por cima do movimento (em vez de substituí-lo)");
                btn("Caber: " + (p.fit() ? "sim" : "não"), x + 18 + 2 * bw, yy + 70, bw, () -> { commit(); draft = draft.withAnim(p.withFit(!p.fit())); reopen(); },
                        "Estica a animação para ocupar exatamente a duração do bloco");
                btn("Velocidade: " + trim(p.speed()) + "x", x + 10, yy + 94, (W - 24) / 2, () -> { commit(); draft = draft.withAnim(p.withSpeed(nextOf(SPEEDS, p.speed()))); reopen(); }, "Velocidade da animação");
                btn("Intensidade: " + (int) Math.round(p.weight() * 100) + "%", x + 14 + (W - 24) / 2, yy + 94, (W - 24) / 2, () -> { commit(); draft = draft.withAnim(p.withWeight(nextOf(WEIGHTS, p.weight()))); reopen(); }, "Quanto a animação manda sobre a pose normal");
            }
            case SWING -> {
                btn("Batidas: " + draft.count(), x + 10, yy, (W - 24) / 2, () -> { commit(); draft = draft.withCount(draft.count() % 8 + 1); reopen(); }, "Quantas vezes balança o braço durante o bloco");
                btn("Mão: " + (draft.offHand() ? "secundária" : "principal"), x + 14 + (W - 24) / 2, yy, (W - 24) / 2, () -> { commit(); draft = draft.withOffHand(!draft.offHand()); reopen(); }, "");
            }
            case USE_ITEM, SWAP_ITEM -> {
                fItem = field(x + 10, yy + 12, W - 20 - 100, draft.itemId());
                btn("Item da mão", x + W - 106, yy + 11, 96, () -> { commit(); handItem(); reopen(); }, "Usa o item que você está segurando agora");
                if (draft.type() == ReactionType.USE_ITEM) {
                    btn("Batidas: " + draft.count(), x + 10, yy + 40, (W - 24) / 2, () -> { commit(); draft = draft.withCount(draft.count() % 8 + 1); reopen(); }, "");
                    btn("Mão: " + (draft.offHand() ? "secundária" : "principal"), x + 14 + (W - 24) / 2, yy + 40, (W - 24) / 2, () -> { commit(); draft = draft.withOffHand(!draft.offHand()); reopen(); }, "");
                }
            }
            case WALK -> {
                btn("Destino: minha posição", x + 10, yy, (W - 24) / 2, () -> { commit(); useMyPosition(); reopen(); },
                        "O ator vai até onde você está agora. Posicione-se no lugar certo e clique.");
                btn("Destino: bloco que miro", x + 14 + (W - 24) / 2, yy, (W - 24) / 2, () -> { commit(); aimFeet(); reopen(); },
                        "O ator vai até o bloco para onde você está mirando (em cima dele).");
                btn("Andar: " + (draft.offHand() ? "esgueirando" : "normal"), x + 10, yy + 24, (W - 24) / 2, () -> { commit(); draft = draft.withOffHand(!draft.offHand()); reopen(); },
                        "Esgueirando = agachado. Sem isso, andar muito rápido vira corrida automaticamente.");
            }
            case TURN -> {
                btn("Olhar para: minha posição", x + 10, yy, (W - 24) / 2, () -> { commit(); useMyPosition(); reopen(); }, "O ator vira o corpo para onde você está agora.");
                btn("Olhar para: bloco que miro", x + 14 + (W - 24) / 2, yy, (W - 24) / 2, () -> { commit(); aimFeet(); reopen(); }, "O ator vira o corpo para o bloco que você está mirando.");
            }
            case LOOK_AT -> btn("Capturar mira (onde você olha)", x + 10, yy, W - 20, () -> { commit(); captureAim(); reopen(); },
                    "Mire num bloco ou entidade antes de abrir esta tela: o ator olhará para lá. Entidades são seguidas se mexerem.");
        }

        int bw = (W - 20 - 12) / 4, by = y + H - 28;
        btn("Aplicar", x + 10, by, bw, () -> { if (apply()) reopen(); }, "Aplica e continua editando (o ator mostra o resultado)");
        btn("Concluir", x + 14 + bw, by, bw, () -> { if (apply()) client.setScreen(parent); }, "");
        btn("Remover", x + 18 + 2 * bw, by, bw, this::remove, "Apaga esta reação");
        btn("Cancelar", x + 22 + 3 * bw, by, bw, () -> client.setScreen(parent), "Fecha sem aplicar o que ainda não foi aplicado");
    }

    private void btn(String label, int x, int y, int w, Runnable r, String tip) {
        ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), bt -> r.run()).dimensions(x, y, w, 20);
        if (!tip.isEmpty()) b.tooltip(Tooltip.of(Text.literal(tip)));
        addDrawableChild(b.build());
    }

    private TextFieldWidget field(int x, int y, int w, String text) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(""));
        f.setMaxLength(96);
        f.setText(text);
        addDrawableChild(f);
        return f;
    }

    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v); }

    private static double nextOf(double[] c, double cur) {
        for (int i = 0; i < c.length; i++) if (Math.abs(c[i] - cur) < 1e-9) return c[(i + 1) % c.length];
        return c[0];
    }

    private void reopen() { client.setScreen(new ReactionScreen(parent, actorId, draft)); }

    private void handItem() {
        if (client.player == null || client.player.getMainHandStack().isEmpty()) { error = "Você não está segurando nenhum item."; return; }
        draft = draft.withItem(Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString());
    }

    /** De onde este Andar parte: o destino do Andar anterior, ou a posição inicial do ator. */
    private double[] actorStart() {
        Optional<Actor> oa = CameraDirector.get().timeline.actors().byId(actorId);
        if (oa.isEmpty() || !oa.get().isScripted()) return null;
        double[] p = {oa.get().origin().x(), oa.get().origin().y(), oa.get().origin().z()};
        for (Reaction r : oa.get().reactions())
            if (r.type() == ReactionType.WALK && r.id() != draft.id() && r.startMs() < draft.startMs()) { p[0] = r.tx(); p[1] = r.ty(); p[2] = r.tz(); }
        return p;
    }

    /** Ossos do clipe que existem no modelo do Blockbench do ator; nulo se o ator usa o modelo do jogador. */
    private java.util.List<String> modelBones(AnimClip clip) {
        Optional<Actor> oa = CameraDirector.get().timeline.actors().byId(actorId);
        if (oa.isEmpty() || !oa.get().hasModel()) return null;
        GeoModel gm = ModelManager.get().model(oa.get().model());
        if (gm == null) return null;
        java.util.List<String> hit = new java.util.ArrayList<>();
        for (String b : clip.bones().keySet()) if (gm.bone(b).isPresent()) hit.add(b);
        return hit;
    }

    private boolean isScripted() {
        return CameraDirector.get().timeline.actors().byId(actorId).map(Actor::isScripted).orElse(false);
    }

    private void useMyPosition() {
        if (client == null || client.player == null) return;
        draft = draft.withTarget(client.player.getX(), client.player.getY(), client.player.getZ(), "", "minha posição");
    }

    /** O ponto em cima do bloco mirado (onde os pés do ator ficam), ou a minha posição se não houver bloco. */
    private void aimFeet() {
        if (client.crosshairTarget instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
            var bp = bh.getBlockPos();
            draft = draft.withTarget(bp.getX() + 0.5, bp.getY() + 1.0, bp.getZ() + 0.5, "", "bloco");
        } else {
            useMyPosition();
        }
    }

    private void captureAim() {
        HitResult h = client.crosshairTarget;
        if (h instanceof EntityHitResult eh) {
            Entity e = eh.getEntity();
            Vec3d p = e.getEyePos();
            draft = draft.withTarget(p.x, p.y, p.z, e.getUuid().toString(), e.getName().getString());
        } else if (h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK) {
            Vec3d p = bh.getPos();
            draft = draft.withTarget(p.x, p.y, p.z, "", "bloco");
        } else if (client.player != null) {
            double[] f = PathGeometry.forward(new CameraState(0, 0, 0, client.player.getYaw(), client.player.getPitch(), 0, 70));
            draft = draft.withTarget(client.player.getX() + f[0] * 6, client.player.getY() + 1.6 + f[1] * 6, client.player.getZ() + f[2] * 6, "", "ponto");
        }
    }

    /** Lê os campos de texto para o rascunho (mantém o valor antigo se o texto for inválido). */
    private void commit() {
        error = "";
        try { if (fStart != null) draft = draft.withStart(Math.round(Double.parseDouble(fStart.getText().trim().replace(',', '.')) * 1000)); }
        catch (NumberFormatException e) { error = "Início inválido (use segundos, ex.: 2.5)."; }
        try { if (fDur != null) draft = draft.withDuration(Math.round(Double.parseDouble(fDur.getText().trim().replace(',', '.')) * 1000)); }
        catch (NumberFormatException e) { error = "Duração inválida (use segundos, ex.: 1.5)."; }
        if (fItem != null) draft = draft.withItem(fItem.getText().trim());
    }

    private boolean apply() {
        commit();
        if (!error.isEmpty()) return false;
        Optional<Actor> a = CameraDirector.get().timeline.actors().byId(actorId);
        if (a.isEmpty()) { client.setScreen(parent); return false; }
        CameraDirector.get().timeline.updateActor(a.get().withReaction(draft), "Editar reação");
        CameraDirector.get().pause();
        CameraDirector.get().setPlayhead(draft.startMs() + draft.durationMs() / 2);
        return true;
    }

    private void remove() {
        CameraDirector.get().timeline.actors().byId(actorId).ifPresent(a ->
                CameraDirector.get().timeline.updateActor(a.withoutReaction(draft.id()), "Remover reação"));
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int x = px(), y = py();
        ctx.fill(x, y, x + W, y + H, UiLayout.bg());
        ctx.drawBorder(x, y, W, H, 0xFF2A3142);
        ctx.drawCenteredTextWithShadow(textRenderer, "Editar reação", x + W / 2, y + 8, TEXT);
        ctx.drawTextWithShadow(textRenderer, "Início (s)", x + 10, y + 54, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Duração (s)", x + 20 + (W - 30) / 2, y + 54, MUTED);

        int yy = y + 94;
        switch (draft.type()) {
            case ANIMATION -> {
                var clip = ActorManager.get().clips().clip(draft.anim().clip());
                if (draft.anim().clip().isEmpty()) ctx.drawTextWithShadow(textRenderer, "Escolha uma animação.", x + 10, yy + 118, WARN);
                else if (clip.isEmpty()) ctx.drawTextWithShadow(textRenderer, "Animação não encontrada (arquivo removido?).", x + 10, yy + 118, ERROR);
                else if (modelBones(clip.get()) != null) {
                    java.util.List<String> hit = modelBones(clip.get());
                    ctx.drawTextWithShadow(textRenderer, hit.isEmpty() ? "Nenhum osso desta animação existe no modelo do ator." : "Vai mexer nos ossos: " + String.join(", ", hit.size() > 5 ? hit.subList(0, 5) : hit) + (hit.size() > 5 ? "..." : ""),
                            x + 10, yy + 118, hit.isEmpty() ? ERROR : OK);
                }
                else if (!clip.get().affectsPlayer()) ctx.drawTextWithShadow(textRenderer, "Esta animação não mexe em nenhuma parte do jogador.", x + 10, yy + 118, ERROR);
                else {
                    java.util.List<String> parts = new java.util.ArrayList<>();
                    for (String b : clip.get().bones().keySet()) { Part p = Part.fromBone(b); if (p != null && Part.has(draft.anim().partMask(), p)) parts.add(p.label); }
                    ctx.drawTextWithShadow(textRenderer, parts.isEmpty() ? "Nenhuma das partes marcadas existe nesta animação." : "Vai mexer em: " + String.join(", ", parts),
                            x + 10, yy + 118, parts.isEmpty() ? ERROR : OK);
                }
            }
            case USE_ITEM, SWAP_ITEM -> {
                ctx.drawTextWithShadow(textRenderer, "Item (ex.: minecraft:diamond_sword)", x + 10, yy, MUTED);
                String id = fItem == null ? draft.itemId() : fItem.getText().trim();
                Identifier ident = Identifier.tryParse(id);
                boolean ok = id.isEmpty() || (ident != null && Registries.ITEM.containsId(ident));
                ctx.drawTextWithShadow(textRenderer, id.isEmpty() ? "vazio: mão livre" : ok ? "item válido" : "item desconhecido", x + 10, yy + 34 + (draft.type() == ReactionType.USE_ITEM ? 56 : 0), id.isEmpty() ? MUTED : ok ? OK : ERROR);
            }
            case WALK, TURN -> {
                ctx.drawTextWithShadow(textRenderer, "Destino: " + (draft.label().isEmpty() ? "(nenhum)" : draft.label()), x + 10, yy + (draft.type() == ReactionType.WALK ? 52 : 28), TEXT);
                ctx.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "x %.1f  y %.1f  z %.1f", draft.tx(), draft.ty(), draft.tz()), x + 10, yy + (draft.type() == ReactionType.WALK ? 64 : 40), MUTED);
                if (draft.type() == ReactionType.WALK) {
                    double[] from = actorStart();
                    if (from != null) {
                        double dist = Math.hypot(draft.tx() - from[0], draft.tz() - from[2]);
                        double sp = dist / (draft.durationMs() / 1000.0);
                        ctx.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "%.1f blocos em %.1f s = %.1f blocos/s (%s)", dist, draft.durationMs() / 1000.0, sp,
                                draft.offHand() ? "esgueirando" : sp > ScriptedMotion.SPRINT_BLOCKS_PER_S ? "corrida" : sp < 0.3 ? "quase parado" : "andando"), x + 10, yy + 80, sp > 12 ? WARN : OK);
                    }
                }
                if (!isScripted()) ctx.drawTextWithShadow(textRenderer, "Só atores roteirizados usam este bloco.", x + 10, yy + 100, ERROR);
            }
            case LOOK_AT -> {
                String where = draft.targetEntity().isEmpty() ? "ponto fixo" : "segue a entidade";
                ctx.drawTextWithShadow(textRenderer, "Alvo: " + (draft.label().isEmpty() ? "(nenhum)" : draft.label()) + " (" + where + ")", x + 10, yy + 28, TEXT);
                ctx.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "x %.1f  y %.1f  z %.1f", draft.tx(), draft.ty(), draft.tz()), x + 10, yy + 40, MUTED);
            }
            default -> { }
        }
        if (!error.isEmpty()) ctx.drawTextWithShadow(textRenderer, error, x + 10, y + H - 40, ERROR);
        super.render(ctx, mx, my, delta);
        UiLayout.drawHandle(ctx, px(), py(), W, H, resizing || UiLayout.overHandle(mx, my, px(), py(), W, H));
        if (resizing) UiLayout.drawGhost(ctx, mx, height, resizeW, textRenderer);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && UiLayout.overHandle(mx, my, px(), py(), W, H)) { resizing = true; resizeW = W; return true; }
        return super.mouseClicked(mx, my, button);
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
            commit();
            UiLayout.commitWidth(resizeW);
            reopen(); // refaz a tela com a largura nova, mantendo o que foi digitado
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void removed() { CameraDirector.get().setEditorOpen(false); }

    @Override
    public void close() { client.setScreen(parent); }
}
