package dev.timescute.mixin;

import dev.timescute.client.CameraDirector;
import dev.timescute.client.RenderSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void timescute$fov(Camera camera, float tickDelta, boolean changingFov,
                            CallbackInfoReturnable<Double> cir) {
        CameraDirector.get().current().ifPresent(s -> cir.setReturnValue(s.fov()));
    }

    /** Fim do quadro: a imagem completa já está no framebuffer principal. */
    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void timescute$afterFrame(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        RenderSession.get().afterFrame(MinecraftClient.getInstance());
    }
}
