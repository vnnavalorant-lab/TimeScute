package dev.timescute.client;

import dev.timescute.core.PathFiles;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Janela simples para dar nome e salvar o trajeto. */
public final class SavePathScreen extends Screen {
    private final TimelineScreen parent;
    private final CameraDirector dir = CameraDirector.get();
    private TextFieldWidget field;
    private String message = "";
    private boolean messageIsError;
    private String armedOverwrite; // nome que o usuário já foi avisado que será substituído

    public SavePathScreen(TimelineScreen parent) {
        super(Text.literal("Salvar trajeto"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        dir.setEditorOpen(true);
        int cx = width / 2, cy = height / 2;
        field = new TextFieldWidget(textRenderer, cx - 110, cy - 10, 220, 20, Text.literal("Nome"));
        field.setMaxLength(48);
        field.setText(dir.pathName() != null ? dir.pathName() : "Meu trajeto");
        addDrawableChild(field);
        setInitialFocus(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("Salvar"), b -> save()).dimensions(cx - 110, cy + 18, 106, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancelar"), b -> close()).dimensions(cx + 4, cy + 18, 106, 20).build());
    }

    private void save() {
        String name = PathFiles.sanitize(field.getText());
        boolean exists = PathFiles.exists(dir.pathsDir(), name);
        if (exists && !name.equals(dir.pathName()) && !name.equals(armedOverwrite)) {
            armedOverwrite = name;
            message = "Já existe \"" + name + "\". Clique em Salvar de novo para substituir.";
            messageIsError = true;
            return;
        }
        try {
            PathFiles.save(dir.pathsDir(), name, dir.timeline);
            dir.setPathName(name);
            dir.toast("Salvo: " + name, false);
            client.setScreen(parent);
        } catch (Exception e) {
            message = "Não foi possível salvar: " + e.getMessage();
            messageIsError = true;
        }
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        int cx = width / 2, cy = height / 2;
        ctx.fill(cx - 130, cy - 48, cx + 130, cy + 52, 0xEE12151C);
        ctx.drawBorder(cx - 130, cy - 48, 260, 100, 0xFF2A3142);
        ctx.drawCenteredTextWithShadow(textRenderer, "Salvar trajeto", cx, cy - 38, 0xFFE6EAF2);
        super.render(ctx, mx, my, delta);
        if (!message.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, message, cx, cy + 46, messageIsError ? 0xFFFF6B6B : 0xFF8A93A6);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { save(); return true; }
        boolean r = super.keyPressed(key, scan, mods);
        if (!field.getText().isEmpty() && armedOverwrite != null && !PathFiles.sanitize(field.getText()).equals(armedOverwrite)) {
            armedOverwrite = null;
            message = "";
        }
        return r;
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
