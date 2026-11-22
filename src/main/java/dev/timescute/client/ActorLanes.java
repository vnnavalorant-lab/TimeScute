package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Uma faixa por ator na timeline: a barra da performance gravada e, por cima, os blocos de reação
 * (arraste para mover, puxe a borda direita para mudar a duração, botão direito para editar).
 */
final class ActorLanes {
    /** O que a tela da timeline fornece. */
    interface Host {
        int x0();
        int x1();
        int width();
        int top();
        int sx(long ms);
        long msAt(double screenX);
        long snapMs(long ms);
        TextRenderer font();
        void scrubTo(double screenX);
        void clearKeySelection();
        void openReaction(long actorId, long reactionId);
    }

    static final int LANE_H = 34, EDGE_PX = 5;
    /** Quantas faixas de atores cabem de uma vez (Configurações → Interface). */
    static int maxVisible() { return ModSettings.get().lanesVisible; }
    private static final int[] PALETTE = {0xFF4DA3FF, 0xFFE8A33D, 0xFF6BD68B, 0xFFE0626A, 0xFFB388FF, 0xFF26C6DA};
    private static final int TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, DIM = 0xFF565E70;

    private enum Drag { NONE, MOVE_REACTION, RESIZE_REACTION, MOVE_PERF }

    private final Host host;
    private final Timeline tl = CameraDirector.get().timeline;
    private int scroll;
    private Long selActor, selReaction;
    private boolean selPerf;
    private Drag drag = Drag.NONE;
    private long dragActor, dragReaction, grabMs, pvStart, pvDur;
    private long lastClickMs;
    private double lastClickX;
    private ReactionType lastType = ReactionType.ANIMATION;

    ActorLanes(Host host) { this.host = host; }

    static int colorOfType(ReactionType t) {
        return switch (t) {
            case ANIMATION -> 0xFF7E57C2;
            case SWING -> 0xFFEF5350;
            case USE_ITEM -> 0xFFFFA726;
            case SWAP_ITEM -> 0xFF66BB6A;
            case LOOK_AT -> 0xFF42A5F5;
            case WALK -> 0xFF26A69A;
            case TURN -> 0xFF8D6E63;
        };
    }

    // ================= geometria =================

    private List<Actor> actors() { return tl.actors().all(); }
    int visibleCount() { return Math.min(maxVisible(), actors().size()); }
    int height() { return visibleCount() * LANE_H; }
    boolean contains(double mx, double my) { return my >= host.top() && my < host.top() + height() && mx >= 0 && mx <= host.width(); }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, Math.max(0, actors().size() - maxVisible()))); }

    // ================= seleção =================

    Optional<Actor> selectedActor() { return selActor == null ? Optional.empty() : tl.actors().byId(selActor); }

    Optional<Reaction> selectedReaction() {
        if (selActor == null || selReaction == null) return Optional.empty();
        return selectedActor().flatMap(a -> a.reaction(selReaction));
    }

    void select(long actorId, Long reactionId) {
        selActor = actorId;
        selReaction = reactionId;
        selPerf = false;
        ActorRecorder.get().setLastActor(actorId);
    }

    void clearSelection() { selActor = null; selReaction = null; selPerf = false; }

    /** Texto para a linha de informações, ou null se não há seleção de ator. */
    String info() {
        Optional<Reaction> r = selectedReaction();
        if (r.isPresent()) {
            Reaction x = r.get();
            return String.format(Locale.ROOT, "Reação \"%s\" (%s) em %s, %.1f s", x.shortLabel(), x.type().label, TimelineView.format(x.startMs()), x.durationMs() / 1000.0);
        }
        Optional<Actor> a = selectedActor();
        if (a.isEmpty()) return null;
        Actor x = a.get();
        return x.hasPerformance()
                ? String.format(Locale.ROOT, "Ator \"%s\": performance de %s, começa em %s | %d reação(ões)", x.name(), Fmt.duration(x.performance().durationMs()), TimelineView.format(x.startMs()), x.reactions().size())
                : x.isScripted()
                ? "Ator \"" + x.name() + "\": roteirizado, " + x.reactions().size() + " bloco(s) | duplo clique na faixa cria um bloco (Andar até, Virar...)"
                : "Ator \"" + x.name() + "\": ainda sem gravação (use Gravar ou crie um ator de roteiro)";
    }

    // ================= ações =================

    /** Cria uma reação no instante dado. Devolve a reação criada (ou vazio se não há ator). */
    Optional<Reaction> addReaction(long actorId, long startMs, ReactionType type) {
        Optional<Actor> oa = tl.actors().byId(actorId);
        if (oa.isEmpty()) return Optional.empty();
        lastType = type;
        Reaction r = Reaction.of(tl.newId(), startMs, type == ReactionType.SWING ? 600 : 1500, type);
        switch (type) {
            case ANIMATION -> r = r.withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE));
            case SWAP_ITEM -> r = r.withItem("minecraft:diamond_sword");
            case LOOK_AT -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    double[] f = PathGeometry.forward(new CameraState(0, 0, 0, mc.player.getYaw(), mc.player.getPitch(), 0, 70));
                    r = r.withTarget(mc.player.getX() + f[0] * 5, mc.player.getY() + 1.6 + f[1] * 5, mc.player.getZ() + f[2] * 5, "", "ponto");
                }
            }
            case WALK, TURN -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) r = r.withTarget(mc.player.getX(), mc.player.getY(), mc.player.getZ(), "", "minha posição");
                if (type == ReactionType.WALK) r = Reaction.of(r.id(), r.startMs(), 3000, type).withTarget(r.tx(), r.ty(), r.tz(), "", r.label());
            }
            default -> { }
        }
        tl.updateActor(oa.get().withReaction(r), "Adicionar reação");
        select(actorId, r.id());
        host.clearKeySelection();
        return Optional.of(r);
    }

    /** Cria uma reação no ponto de reprodução para o ator selecionado (ou o primeiro). */
    Optional<Reaction> addAtPlayhead(long playheadMs) {
        long id = selActor != null && tl.actors().byId(selActor).isPresent() ? selActor : (actors().isEmpty() ? -1 : actors().get(0).id());
        return id < 0 ? Optional.empty() : addReaction(id, playheadMs, lastType);
    }

    boolean deleteSelected() {
        Optional<Reaction> r = selectedReaction();
        if (r.isEmpty()) return false;
        tl.updateActor(selectedActor().get().withoutReaction(r.get().id()), "Remover reação");
        selReaction = null;
        return true;
    }

    boolean duplicateSelected() {
        Optional<Reaction> r = selectedReaction();
        if (r.isEmpty()) return false;
        Reaction x = r.get();
        Reaction copy = new Reaction(tl.newId(), x.endMs(), x.durationMs(), x.type(), x.anim(), x.itemId(), x.offHand(), x.count(),
                x.tx(), x.ty(), x.tz(), x.targetEntity(), x.label());
        tl.updateActor(selectedActor().get().withReaction(copy), "Duplicar reação");
        selReaction = copy.id();
        return true;
    }

    boolean wheel(double mx, double my, double amount) {
        if (!contains(mx, my) || actors().size() <= maxVisible()) return false;
        scroll -= (int) Math.signum(amount);
        clampScroll();
        return true;
    }

    // ================= mouse =================

    private int rx(long ms) { return host.sx(ms); }

    private int blockW(long start, long dur) { return Math.max(6, rx(start + dur) - rx(start)); }

    boolean click(double mx, double my, int button) {
        clampScroll();
        int ai = scroll + (int) ((my - host.top()) / LANE_H);
        if (ai < 0 || ai >= actors().size()) return false;
        Actor a = actors().get(ai);

        if (mx < host.x0()) { select(a.id(), null); host.clearKeySelection(); return true; } // nome na lateral
        if (mx > host.x1()) return false;

        for (int i = a.reactions().size() - 1; i >= 0; i--) { // blocos de cima para baixo
            Reaction r = a.reactions().get(i);
            int x = rx(r.startMs());
            if (mx >= x && mx <= x + blockW(r.startMs(), r.durationMs())) {
                select(a.id(), r.id());
                host.clearKeySelection();
                if (button == 1) { host.openReaction(a.id(), r.id()); return true; }
                if (button == 0) {
                    dragActor = a.id();
                    dragReaction = r.id();
                    pvStart = r.startMs();
                    pvDur = r.durationMs();
                    grabMs = host.msAt(mx) - r.startMs();
                    drag = mx >= x + blockW(r.startMs(), r.durationMs()) - EDGE_PX ? Drag.RESIZE_REACTION : Drag.MOVE_REACTION;
                }
                return true;
            }
        }

        if (a.hasPerformance() && mx >= rx(a.startMs()) && mx <= rx(a.performanceEndMs())) {
            select(a.id(), null);
            selPerf = true;
            host.clearKeySelection();
            if (button == 0) { drag = Drag.MOVE_PERF; dragActor = a.id(); pvStart = a.startMs(); grabMs = host.msAt(mx) - a.startMs(); }
            return true;
        }

        select(a.id(), null);
        host.clearKeySelection();
        if (button == 0) {
            long now = System.currentTimeMillis();
            boolean dbl = now - lastClickMs < 350 && Math.abs(mx - lastClickX) < 6;
            lastClickMs = dbl ? 0 : now;
            lastClickX = mx;
            if (dbl) addReaction(a.id(), host.snapMs(host.msAt(mx)), lastType); // duplo clique no vazio: nova reação
            else host.scrubTo(mx);
        }
        return true;
    }

    boolean dragging() { return drag != Drag.NONE; }

    boolean drag(double mx) {
        if (drag == Drag.NONE) return false;
        long t = host.msAt(mx);
        switch (drag) {
            case MOVE_REACTION, MOVE_PERF -> pvStart = Math.max(0, host.snapMs(t - grabMs));
            case RESIZE_REACTION -> pvDur = Math.max(Reaction.MIN_DURATION_MS, host.snapMs(t) - pvStart);
            default -> { }
        }
        return true;
    }

    boolean release() {
        if (drag == Drag.NONE) return false;
        Drag d = drag;
        drag = Drag.NONE;
        Optional<Actor> oa = tl.actors().byId(dragActor);
        if (oa.isEmpty()) return true;
        Actor a = oa.get();
        if (d == Drag.MOVE_PERF) {
            if (pvStart != a.startMs()) tl.updateActor(a.withStart(pvStart), "Mover performance");
        } else {
            a.reaction(dragReaction).ifPresent(r -> {
                Reaction n = d == Drag.RESIZE_REACTION ? r.withDuration(pvDur) : r.withStart(pvStart);
                if (!n.equals(r)) tl.updateActor(a.withReaction(n), d == Drag.RESIZE_REACTION ? "Mudar duração da reação" : "Mover reação");
            });
        }
        return true;
    }

    // ================= desenho =================

    List<Text> draw(DrawContext ctx, int mx, int my, List<Text> tip) {
        clampScroll(); // o número de faixas visíveis pode ter mudado
        clampScroll();
        TextRenderer font = host.font();
        List<Actor> as = actors();
        for (int i = 0; i < visibleCount(); i++) {
            Actor a = as.get(scroll + i);
            int y = host.top() + i * LANE_H, color = PALETTE[(scroll + i) % PALETTE.length];
            boolean sel = selActor != null && selActor == a.id();
            ctx.fill(0, y, host.width(), y + LANE_H, i % 2 == 0 ? 0xFF161A23 : 0xFF141821);
            ctx.fill(0, y, 3, y + LANE_H, sel ? color : 0xFF2A3142);
            ctx.fill(8, y + 6, 14, y + 12, color);
            ctx.drawTextWithShadow(font, ellipsize(font, a.name(), host.x0() - 24), 18, y + 5, sel ? TEXT : MUTED);
            String sub = a.muted() ? "(oculto)" : a.hasPerformance() ? Fmt.duration(a.performance().durationMs()) : a.isScripted() ? "roteiro" : "sem gravação";
            ctx.drawText(font, sub, 18, y + 18, (a.hasPerformance() || a.isScripted()) && !a.muted() ? DIM : 0xFFB0644F, false);

            ctx.enableScissor(host.x0(), y, host.x1(), y + LANE_H);
            if (a.hasPerformance()) {
                long s = drag == Drag.MOVE_PERF && dragActor == a.id() ? pvStart : a.startMs();
                int x1 = rx(s), x2 = rx(s + a.performance().durationMs());
                ctx.fill(x1, y + 11, x2, y + LANE_H - 11, (color & 0x00FFFFFF) | (a.muted() ? 0x22000000 : 0x55000000));
                ctx.drawBorder(x1, y + 11, Math.max(2, x2 - x1), LANE_H - 22, (color & 0x00FFFFFF) | (selPerf && sel ? 0xFF000000 : 0x99000000));
                if (x2 - x1 > 70) ctx.drawText(font, "performance", x1 + 4, y + 13, MUTED, false);
            }
            for (Reaction r : a.reactions()) {
                boolean dragged = drag != Drag.NONE && drag != Drag.MOVE_PERF && dragActor == a.id() && dragReaction == r.id();
                long st = dragged ? pvStart : r.startMs(), du = dragged ? pvDur : r.durationMs();
                int x = rx(st), w = blockW(st, du);
                boolean chosen = sel && selReaction != null && selReaction == r.id();
                ctx.fill(x, y + 4, x + w, y + LANE_H - 4, (colorOfType(r.type()) & 0x00FFFFFF) | (dragged ? 0x99000000 : 0xDD000000));
                if (chosen) ctx.drawBorder(x - 1, y + 3, w + 2, LANE_H - 6, 0xFFFFFFFF);
                ctx.fill(x + w - 2, y + 4, x + w, y + LANE_H - 4, 0x55FFFFFF); // alça de redimensionar
                ctx.drawText(font, ellipsize(font, r.shortLabel(), w - 6), x + 3, y + 12, 0xFFFFFFFF, false);
            }
            ctx.disableScissor();
        }
        if (as.size() > maxVisible())
            ctx.drawText(font, (scroll + 1) + "-" + (scroll + maxVisible()) + "/" + as.size() + " (roda)", 18, host.top() + height() - 10, DIM, false);

        if (drag == Drag.NONE && contains(mx, my) && mx >= host.x0() && mx <= host.x1()) {
            int ai = scroll + (my - host.top()) / LANE_H;
            if (ai >= 0 && ai < as.size()) {
                Actor a = as.get(ai);
                for (int i = a.reactions().size() - 1; i >= 0; i--) {
                    Reaction r = a.reactions().get(i);
                    int x = rx(r.startMs());
                    if (mx >= x && mx <= x + blockW(r.startMs(), r.durationMs())) {
                        return List.of(Text.literal(r.type().label + ": " + r.shortLabel()),
                                Text.literal(String.format(Locale.ROOT, "%s | %.1f s", TimelineView.format(r.startMs()), r.durationMs() / 1000.0)),
                                Text.literal("Arraste: mover | Borda direita: duração | Botão direito: editar"));
                    }
                }
            }
        }
        return tip;
    }

    private static String ellipsize(TextRenderer f, String s, int maxPx) {
        if (maxPx <= 6) return "";
        if (f.getWidth(s) <= maxPx) return s;
        while (s.length() > 1 && f.getWidth(s + "..") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "..";
    }
}
