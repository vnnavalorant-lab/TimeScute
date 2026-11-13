package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Biblioteca de replays: miniatura, duração, tamanho, origem, busca e ações. */
public final class ReplayListScreen extends Screen {
    private static final int ROW_H = 56, THUMB_W = 80, THUMB_H = 45, TOP = 56, BAR_H = 54, MARGIN = 8;
    private static final int TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, DIM = 0xFF565E70, ERROR = 0xFFFF6B6B, ACCENT = 0xFF4DA3FF;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final long DOUBLE_CLICK_MS = 350, ARM_MS = 4000, TOAST_MS = 4000;

    private enum Sort {
        RECENTE("Mais recentes"), NOME("Nome"), TAMANHO("Tamanho"), DURACAO("Duração");
        final String label;
        Sort(String l) { label = l; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private record Tex(Identifier id, int w, int h) { }

    private final Screen parent;
    private final RecordingManager rec = RecordingManager.get();
    private final Path dir = rec.replaysDir();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timescute-replay-list");
        t.setDaemon(true);
        return t;
    });

    private List<ReplayLibrary.Entry> all = List.of(), shown = List.of();
    private boolean loading = true;
    private String loadError = "";
    private Sort sort = Sort.RECENTE;
    private Path selected;
    private String pendingSelectName;
    private int scroll;

    private Path armedDelete;
    private long armedUntil;
    private String toast = "";
    private boolean toastError;
    private long toastUntil;
    private long lastClickMs;
    private Path lastClickPath;

    private final Map<Path, byte[]> thumbBytes = new ConcurrentHashMap<>();
    private final Set<Path> thumbRequested = ConcurrentHashMap.newKeySet();
    private final Map<Path, Tex> textures = new HashMap<>();
    private final Set<Path> noThumb = new HashSet<>();
    private int textureCounter;

    private TextFieldWidget search;
    private ButtonWidget bSort, bPlay, bRename, bDelete, bRepair, bFolder, bAuto, bSolo, bBack;

    public ReplayListScreen(Screen parent) {
        super(Text.literal("Replays"));
        this.parent = parent;
    }

    // ================= ciclo de vida =================

    @Override
    protected void init() {
        search = new TextFieldWidget(textRenderer, MARGIN, 28, Math.max(100, Math.min(220, width / 3)), 18, Text.literal("Buscar"));
        search.setSuggestion("Buscar por nome, servidor ou jogador");
        search.setChangedListener(s -> { search.setSuggestion(s.isEmpty() ? "Buscar por nome, servidor ou jogador" : ""); applyFilter(); });
        addDrawableChild(search);

        bSort = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> { sort = sort.next(); applyFilter(); })
                .dimensions(MARGIN + search.getWidth() + 6, 27, 120, 20).tooltip(Tooltip.of(Text.literal("Muda a ordem da lista"))).build());

        int bw = Math.max(70, Math.min(112, (width - 2 * MARGIN - 4 * 4) / 5));
        int total = bw * 5 + 4 * 4, x = (width - total) / 2, y1 = height - BAR_H + 2, y2 = height - BAR_H + 26;
        bPlay = addDrawableChild(ButtonWidget.builder(Text.literal("Reproduzir"), b -> onPlay())
                .dimensions(x, y1, bw, 20).tooltip(Tooltip.of(Text.literal("A reprodução chega na fase 4"))).build());
        bRename = addDrawableChild(ButtonWidget.builder(Text.literal("Renomear"), b -> onRename()).dimensions(x + (bw + 4), y1, bw, 20).build());
        bDelete = addDrawableChild(ButtonWidget.builder(Text.literal("Excluir"), b -> onDelete()).dimensions(x + 2 * (bw + 4), y1, bw, 20).build());
        bRepair = addDrawableChild(ButtonWidget.builder(Text.literal("Reparar"), b -> onRepair())
                .dimensions(x + 3 * (bw + 4), y1, bw, 20).tooltip(Tooltip.of(Text.literal("Fecha direito um replay que ficou incompleto após uma falha"))).build());
        bFolder = addDrawableChild(ButtonWidget.builder(Text.literal("Abrir pasta"), b -> onFolder()).dimensions(x + 4 * (bw + 4), y1, bw, 20).build());

        int w2 = Math.max(110, Math.min(170, (width - 2 * MARGIN - 8) / 3));
        int x2 = (width - (w2 * 3 + 8)) / 2;
        bAuto = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> {
            rec.settings().autoRecord = !rec.settings().autoRecord;
            rec.saveSettings();
        }).dimensions(x2, y2, w2, 20).tooltip(Tooltip.of(Text.literal("Grava sozinho ao entrar em um mundo ou servidor"))).build());
        bSolo = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> {
            rec.settings().recordSingleplayer = !rec.settings().recordSingleplayer;
            rec.saveSettings();
        }).dimensions(x2 + w2 + 4, y2, w2, 20).tooltip(Tooltip.of(Text.literal("Inclui mundos de um jogador na gravação automática"))).build());
        bBack = addDrawableChild(ButtonWidget.builder(Text.literal("Voltar"), b -> close()).dimensions(x2 + 2 * (w2 + 4), y2, w2, 20).build());

        reload();
    }

    @Override
    public void removed() {
        io.shutdownNow();
        for (Tex t : textures.values()) client.getTextureManager().destroyTexture(t.id());
        textures.clear();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    // ================= dados =================

    private void reload() {
        loading = true;
        loadError = "";
        io.submit(() -> {
            try {
                List<ReplayLibrary.Entry> list = ReplayLibrary.scan(dir);
                client.execute(() -> { all = list; loading = false; applyFilter(); });
            } catch (IOException e) {
                client.execute(() -> { all = List.of(); loading = false; loadError = "Não foi possível listar: " + e.getMessage(); applyFilter(); });
            }
        });
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<ReplayLibrary.Entry> out = new ArrayList<>();
        for (ReplayLibrary.Entry e : all) {
            if (q.isEmpty() || haystack(e).contains(q)) out.add(e);
        }
        Comparator<ReplayLibrary.Entry> cmp = switch (sort) {
            case RECENTE -> Comparator.comparingLong(ReplayLibrary.Entry::sortTimeMs).reversed();
            case NOME -> Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
            case TAMANHO -> Comparator.comparingLong(ReplayLibrary.Entry::sizeBytes).reversed();
            case DURACAO -> Comparator.comparingLong(ReplayLibrary.Entry::durationMs).reversed();
        };
        out.sort(cmp);
        shown = out;

        if (pendingSelectName != null) {
            for (ReplayLibrary.Entry e : shown) if (e.name().equals(pendingSelectName)) selected = e.file();
            pendingSelectName = null;
        }
        if (selected != null && shown.stream().noneMatch(e -> e.file().equals(selected))) selected = null;
        clampScroll();
    }

    private static String haystack(ReplayLibrary.Entry e) {
        StringBuilder b = new StringBuilder(e.name());
        if (e.meta() != null) b.append(' ').append(e.meta().where()).append(' ').append(e.meta().server).append(' ').append(e.meta().playerName);
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private ReplayLibrary.Entry selectedEntry() {
        if (selected == null) return null;
        for (ReplayLibrary.Entry e : shown) if (e.file().equals(selected)) return e;
        return null;
    }

    private void say(String msg, boolean error) {
        toast = msg;
        toastError = error;
        toastUntil = System.currentTimeMillis() + TOAST_MS;
    }

    // ================= ações =================

    private void onPlay() {
        say("A reprodução de replays chega na fase 4.", false);
    }

    private void onRename() {
        ReplayLibrary.Entry e = selectedEntry();
        if (e == null) return;
        client.setScreen(new PromptScreen(this, "Renomear replay", e.name(), text -> {
            try {
                Path np = ReplayLibrary.rename(e.file(), text);
                pendingSelectName = np.getFileName().toString().replaceAll("\\.tsr$", "");
                reload();
                say("Renomeado.", false);
                return null;
            } catch (IOException ex) {
                return ex.getMessage();
            }
        }));
    }

    private void onDelete() {
        ReplayLibrary.Entry e = selectedEntry();
        if (e == null) return;
        long now = System.currentTimeMillis();
        if (!e.file().equals(armedDelete) || now > armedUntil) {
            armedDelete = e.file();
            armedUntil = now + ARM_MS;
            return;
        }
        armedDelete = null;
        io.submit(() -> {
            try {
                ReplayLibrary.delete(e.file());
                client.execute(() -> { say("Excluído: " + e.name(), false); selected = null; reload(); });
            } catch (IOException ex) {
                client.execute(() -> say("Não foi possível excluir: " + ex.getMessage(), true));
            }
        });
    }

    private void onRepair() {
        ReplayLibrary.Entry e = selectedEntry();
        if (e == null || !e.readable() || e.complete()) return;
        io.submit(() -> {
            try {
                boolean did = ReplayRepair.repair(e.file());
                client.execute(() -> { say(did ? "Replay reparado." : "Esse replay já estava íntegro.", false); reload(); });
            } catch (IOException ex) {
                client.execute(() -> say("Não foi possível reparar: " + ex.getMessage(), true));
            }
        });
    }

    private void onFolder() {
        try {
            Files.createDirectories(dir);
            Util.getOperatingSystem().open(dir.toFile());
        } catch (IOException ex) {
            say("Não foi possível abrir a pasta: " + ex.getMessage(), true);
        }
    }

    // ================= layout =================

    private int listBottom() { return height - BAR_H - 6; }
    private int viewH() { return listBottom() - TOP; }
    private int maxScroll() { return Math.max(0, shown.size() * ROW_H - viewH()); }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, maxScroll())); }

    private void ensureVisible(int idx) {
        int top = idx * ROW_H, bottom = top + ROW_H;
        if (top < scroll) scroll = top;
        else if (bottom > scroll + viewH()) scroll = bottom - viewH();
        clampScroll();
    }

    // ================= miniaturas =================

    private Tex thumbFor(Path p) {
        Tex t = textures.get(p);
        if (t != null || noThumb.contains(p)) return t;
        byte[] b = thumbBytes.get(p);
        if (b == null) {
            if (thumbRequested.add(p)) io.submit(() -> thumbBytes.put(p, ReplayLibrary.thumbnail(p).orElse(new byte[0])));
            return null;
        }
        if (b.length == 0) { noThumb.add(p); return null; }
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(b));
            Identifier id = new Identifier("timescute", "thumb/" + (textureCounter++));
            client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(img));
            Tex nt = new Tex(id, img.getWidth(), img.getHeight());
            textures.put(p, nt);
            return nt;
        } catch (IOException | RuntimeException e) {
            noThumb.add(p);
            return null;
        }
    }

    // ================= desenho =================

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        ctx.drawCenteredTextWithShadow(textRenderer, "Replays", width / 2, 10, TEXT);

        long total = 0;
        for (ReplayLibrary.Entry e : all) total += e.sizeBytes();
        String info = shown.size() == all.size()
                ? all.size() + " replays | " + ReplayLibrary.formatSize(total)
                : shown.size() + " de " + all.size() + " | " + ReplayLibrary.formatSize(total);
        ctx.drawTextWithShadow(textRenderer, info, width - MARGIN - textRenderer.getWidth(info), 33, MUTED);
        if (rec.isRecording()) {
            String r = "Gravando agora";
            ctx.drawTextWithShadow(textRenderer, r, width - MARGIN - textRenderer.getWidth(r), 12, 0xFFFF3B3B);
        }

        updateButtons();

        int top = TOP, bottom = listBottom();
        ctx.fill(MARGIN, top - 2, width - MARGIN, bottom + 2, 0x66000000);
        ctx.enableScissor(MARGIN, top, width - MARGIN, bottom);
        int created = 0;
        for (int i = 0; i < shown.size(); i++) {
            int y = top + i * ROW_H - scroll;
            if (y + ROW_H < top || y > bottom) continue;
            ReplayLibrary.Entry e = shown.get(i);
            boolean fresh = !textures.containsKey(e.file()) && !noThumb.contains(e.file());
            if (fresh && created >= 2) { drawRow(ctx, e, y, mx, my, false); continue; } // no máximo 2 texturas novas por frame
            if (fresh) created++;
            drawRow(ctx, e, y, mx, my, true);
        }
        ctx.disableScissor();

        if (shown.isEmpty()) {
            String msg = loading ? "Carregando…"
                    : !loadError.isEmpty() ? loadError
                    : all.isEmpty() ? "Nenhum replay ainda. Entre em um mundo ou servidor com a gravação automática ligada."
                    : "Nenhum replay corresponde à busca.";
            ctx.drawCenteredTextWithShadow(textRenderer, msg, width / 2, top + 20, loadError.isEmpty() ? MUTED : ERROR);
        }

        if (maxScroll() > 0) { // barra de rolagem
            int bh = Math.max(20, (int) ((long) viewH() * viewH() / (shown.size() * ROW_H)));
            int by = top + (int) ((long) (viewH() - bh) * scroll / maxScroll());
            ctx.fill(width - MARGIN - 4, by, width - MARGIN - 1, by + bh, 0xAA8A93A6);
        }

        if (System.currentTimeMillis() < toastUntil)
            ctx.drawCenteredTextWithShadow(textRenderer, toast, width / 2, listBottom() + 8 - 0, toastError ? ERROR : ACCENT);

        super.render(ctx, mx, my, delta);
    }

    private void updateButtons() {
        ReplayLibrary.Entry e = selectedEntry();
        bSort.setMessage(Text.literal("Ordem: " + sort.label));
        bPlay.active = false; // fase 4
        bRename.active = e != null;
        bDelete.active = e != null;
        bRepair.active = e != null && e.readable() && !e.complete();
        boolean armed = e != null && e.file().equals(armedDelete) && System.currentTimeMillis() <= armedUntil;
        bDelete.setMessage(Text.literal(armed ? "Confirmar exclusão?" : "Excluir"));
        bAuto.setMessage(Text.literal("Gravação automática: " + (rec.settings().autoRecord ? "sim" : "não")));
        bSolo.setMessage(Text.literal("Um jogador: " + (rec.settings().recordSingleplayer ? "sim" : "não")));
    }

    private void drawRow(DrawContext ctx, ReplayLibrary.Entry e, int y, int mx, int my, boolean allowTexture) {
        int x = MARGIN + 2, w = width - 2 * MARGIN - 8;
        boolean sel = e.file().equals(selected);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW_H - 2 && my >= TOP && my < listBottom();
        ctx.fill(x, y, x + w, y + ROW_H - 2, sel ? 0xFF1F3F66 : hover ? 0xFF2B3345 : 0xFF202636);
        if (sel) ctx.drawBorder(x, y, w, ROW_H - 2, ACCENT);

        // miniatura
        int tx = x + 4, ty = y + 4;
        Tex tex = allowTexture ? thumbFor(e.file()) : textures.get(e.file());
        if (tex != null) {
            ctx.drawTexture(tex.id(), tx, ty, THUMB_W, THUMB_H, 0f, 0f, tex.w(), tex.h(), tex.w(), tex.h());
        } else {
            ctx.fill(tx, ty, tx + THUMB_W, ty + THUMB_H, 0xFF11141B);
            String ph = "sem imagem";
            ctx.drawTextWithShadow(textRenderer, ph, tx + (THUMB_W - textRenderer.getWidth(ph)) / 2, ty + 18, DIM);
        }

        int cx = tx + THUMB_W + 8;
        ctx.drawTextWithShadow(textRenderer, e.name(), cx, y + 7, e.readable() ? TEXT : ERROR);

        if (!e.readable()) {
            ctx.drawTextWithShadow(textRenderer, "Arquivo ilegível: " + e.error(), cx, y + 21, ERROR);
            ctx.drawTextWithShadow(textRenderer, ReplayLibrary.formatSize(e.sizeBytes()), cx, y + 34, MUTED);
            return;
        }
        ReplayMeta m = e.meta();
        String when = DATE.format(Instant.ofEpochMilli(e.sortTimeMs()));
        ctx.drawTextWithShadow(textRenderer, when + "  |  " + ReplayLibrary.formatDuration(e.durationMs())
                + "  |  " + ReplayLibrary.formatSize(e.sizeBytes()), cx, y + 21, MUTED);
        String where = m.where() + (m.playerName.isEmpty() ? "" : "  |  " + m.playerName)
                + (m.mcVersion.isEmpty() ? "" : "  |  " + m.mcVersion);
        ctx.drawTextWithShadow(textRenderer, where, cx, y + 34, DIM);

        int rx = x + w - 6;
        if (!e.complete()) {
            String b = "INCOMPLETO";
            rx -= textRenderer.getWidth(b);
            ctx.drawTextWithShadow(textRenderer, b, rx, y + 7, ERROR);
            rx -= 10;
        }
        if (m.markers > 0) {
            String b = m.markers + (m.markers == 1 ? " marcador" : " marcadores");
            ctx.drawTextWithShadow(textRenderer, b, rx - textRenderer.getWidth(b), y + 7, 0xFFE8A33D);
        }
    }

    // ================= entrada =================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0 || mx < MARGIN || mx >= width - MARGIN || my < TOP || my >= listBottom()) return false;
        int idx = (int) ((my - TOP + scroll) / ROW_H);
        if (idx < 0 || idx >= shown.size()) { selected = null; return true; }
        Path p = shown.get(idx).file();
        long now = System.currentTimeMillis();
        boolean dbl = p.equals(lastClickPath) && now - lastClickMs < DOUBLE_CLICK_MS;
        selected = p;
        lastClickPath = p;
        lastClickMs = dbl ? 0 : now;
        if (dbl) onPlay();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scroll -= (int) (amount * 24);
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused()) return super.keyPressed(key, scan, mods);
        int idx = -1;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).file().equals(selected)) idx = i;
        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> select(Math.min(shown.size() - 1, idx + 1));
            case GLFW.GLFW_KEY_UP -> select(Math.max(0, idx < 0 ? 0 : idx - 1));
            case GLFW.GLFW_KEY_HOME -> select(0);
            case GLFW.GLFW_KEY_END -> select(shown.size() - 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> onPlay();
            case GLFW.GLFW_KEY_DELETE -> onDelete();
            case GLFW.GLFW_KEY_F2 -> onRename();
            default -> { return super.keyPressed(key, scan, mods); }
        }
        return true;
    }

    private void select(int idx) {
        if (shown.isEmpty() || idx < 0) return;
        selected = shown.get(idx).file();
        ensureVisible(idx);
    }
}
