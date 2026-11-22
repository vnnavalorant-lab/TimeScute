package dev.timescute.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Janela genérica com um campo de texto (usada para renomear). */
public final class PromptScreen extends Screen {
    /** Recebe o texto; devolve uma mensagem de erro (fica na janela) ou null se deu certo. */
    public interface Handler { String apply(String text); }

    private final Screen parent;
    private final String heading, initial;
    private final Handler handler;
    private TextFieldWidget field;
    private String error = "";

    public PromptScreen(Screen parent, String heading, String initial, Handler handler) {
        super(Text.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.initial = initial;
        this.handler = handler;
    }

    @Override
    protected void init() {
        CameraDirector.get().setEditorOpen(true);
        int cx = width / 2, cy = height / 2;
        field = new TextFieldWidget(textRenderer, cx - 110, cy - 10, 220, 20, Text.literal(heading));
        field.setMaxLength(64);
        field.setText(initial);
        addDrawableChild(field);
        setInitialFocus(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("OK"), b -> submit()).dimensions(cx - 110, cy + 18, 106, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancelar"), b -> close()).dimensions(cx + 4, cy + 18, 106, 20).build());
    }

    private void submit() {
        String msg = handler.apply(field.getText());
        if (msg == null) client.setScreen(parent);
        else error = msg;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        int cx = width / 2, cy = height / 2;
        ctx.fill(cx - 130, cy - 48, cx + 130, cy + 52, 0xEE12151C);
        ctx.drawBorder(cx - 130, cy - 48, 260, 100, 0xFF2A3142);
        ctx.drawCenteredTextWithShadow(textRenderer, heading, cx, cy - 38, 0xFFE6EAF2);
        super.render(ctx, mx, my, delta);
        if (!error.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, error, cx, cy + 46, 0xFFFF6B6B);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() { CameraDirector.get().setEditorOpen(false); }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
