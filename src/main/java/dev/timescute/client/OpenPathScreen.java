package dev.timescute.client;

import dev.timescute.core.PathFiles;
import dev.timescute.core.PathJson;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Lista de trajetos salvos: clique abre, "Excluir" pede confirmação. */
public final class OpenPathScreen extends Screen {
    private static final int ROW_H = 22, LIST_W = 320;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TimelineScreen parent;
    private final CameraDirector dir = CameraDirector.get();
    private List<PathFiles.Entry> entries = List.of();
    private int scroll;
    private String message = "";
    private boolean messageIsError;
    private String armedDelete;

    public OpenPathScreen(TimelineScreen parent) {
        super(Text.literal("Abrir trajeto"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        dir.setEditorOpen(true);
        reload();
    }

    private void reload() {
        try {
            entries = PathFiles.list(dir.pathsDir());
        } catch (Exception e) {
            entries = List.of();
            message = "Não foi possível listar: " + e.getMessage();
            messageIsError = true;
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - visibleRows())));
    }

    private int listX() { return width / 2 - LIST_W / 2; }
    private int listY() { return 44; }
    private int visibleRows() { return Math.max(1, (height - listY() - 40) / ROW_H); }
    private int deleteW() { return 62; }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        ctx.drawCenteredTextWithShadow(textRenderer, "Abrir trajeto", width / 2, 20, 0xFFE6EAF2);

        int x = listX(), y = listY();
        if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Nenhum trajeto salvo ainda.", width / 2, y + 10, 0xFF8A93A6);
        }
        for (int i = 0; i < visibleRows() && i + scroll < entries.size(); i++) {
            PathFiles.Entry e = entries.get(i + scroll);
            int ry = y + i * ROW_H;
            boolean hover = mx >= x && mx < x + LIST_W && my >= ry && my < ry + ROW_H - 2;
            ctx.fill(x, ry, x + LIST_W, ry + ROW_H - 2, hover ? 0xFF2B3345 : 0xFF202636);
            ctx.drawTextWithShadow(textRenderer, e.name(), x + 8, ry + 7, 0xFFE6EAF2);
            String date = DATE.format(Instant.ofEpochMilli(e.modifiedMs()));
            int dx = x + LIST_W - deleteW() - 8 - textRenderer.getWidth(date);
            ctx.drawTextWithShadow(textRenderer, date, dx, ry + 7, 0xFF8A93A6);
            boolean armed = e.name().equals(armedDelete);
            int bx = x + LIST_W - deleteW() - 2;
            ctx.fill(bx, ry + 2, bx + deleteW(), ry + ROW_H - 4, armed ? 0xFF8C2F39 : 0xFF2A2F3F);
            ctx.drawCenteredTextWithShadow(textRenderer, armed ? "Confirmar?" : "Excluir", bx + deleteW() / 2, ry + 7, 0xFFE6EAF2);
        }
        if (entries.size() > visibleRows())
            ctx.drawCenteredTextWithShadow(textRenderer, "Role para ver mais (" + entries.size() + " trajetos)", width / 2,
                    listY() + visibleRows() * ROW_H + 4, 0xFF565E70);
        if (!message.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, message, width / 2, height - 28, messageIsError ? 0xFFFF6B6B : 0xFF8A93A6);
        ctx.drawCenteredTextWithShadow(textRenderer, "Esc volta ao editor", width / 2, height - 14, 0xFF565E70);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int x = listX(), y = listY();
        if (mx < x || mx >= x + LIST_W || my < y) { armedDelete = null; return true; }
        int row = (int) ((my - y) / ROW_H);
        int idx = row + scroll;
        if (row < 0 || row >= visibleRows() || idx >= entries.size()) { armedDelete = null; return true; }
        PathFiles.Entry e = entries.get(idx);

        if (mx >= x + LIST_W - deleteW() - 2) {
            if (e.name().equals(armedDelete)) {
                try {
                    PathFiles.delete(dir.pathsDir(), e.name());
                    if (e.name().equals(dir.pathName())) dir.setPathName(null);
                    message = "Excluído: " + e.name();
                    messageIsError = false;
                } catch (Exception ex) {
                    message = "Não foi possível excluir: " + ex.getMessage();
                    messageIsError = true;
                }
                armedDelete = null;
                reload();
            } else {
                armedDelete = e.name();
            }
            return true;
        }

        armedDelete = null;
        open(e.name());
        return true;
    }

    private void open(String name) {
        try {
            PathJson.Data d = PathFiles.read(dir.pathsDir(), name);
            dir.timeline.replaceAll(d.keys(), d.speeds(), d.actors(), "Abrir \"" + name + "\"");
            dir.selection.clear();
            dir.pause();
            dir.setPlayhead(0);
            dir.setPathName(name);
            dir.toast("Aberto: " + name + " (Ctrl+Z desfaz)", false);
            parent.requestFit();
            client.setScreen(parent);
        } catch (Exception ex) {
            message = "Não foi possível abrir \"" + name + "\": " + ex.getMessage();
            messageIsError = true;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int max = Math.max(0, entries.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void removed() {
        dir.setEditorOpen(false);
    }
}
