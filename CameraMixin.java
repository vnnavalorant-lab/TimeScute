package dev.timescute.mixin;

import dev.timescute.client.CameraDirector;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow private boolean thirdPerson;

    /** Uma vez por frame: avança a reprodução (independe de tick e de pausa). */
    @Inject(method = "update", at = @At("HEAD"))
    private void timescute$advance(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView,
                                float tickDelta, CallbackInfo ci) {
        CameraDirector.get().advance();
    }

    /** Depois do Minecraft posicionar a câmera, sobrescrevemos com o trajeto. */
    @Inject(method = "update", at = @At("TAIL"))
    private void timescute$override(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView,
                                 float tickDelta, CallbackInfo ci) {
        CameraDirector.get().current().ifPresent(s -> {
            setPos(s.x(), s.y(), s.z());
            setRotation((float) s.yaw(), (float) s.pitch());
            this.thirdPerson = true; // mostra o corpo do jogador, esconde a mão
        });
    }
}
