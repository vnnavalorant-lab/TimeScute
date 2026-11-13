package dev.timescute.mixin;

import dev.timescute.client.ReplaySession;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No cliente, "ser espectador" vem da lista de jogadores do servidor, e a câmera do replay não está nela.
 * Sem isto ela não atravessaria paredes nem voaria direito.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Inject(method = "isSpectator", at = @At("HEAD"), cancellable = true)
    private void timescute$cameraIsSpectator(CallbackInfoReturnable<Boolean> cir) {
        if (ReplaySession.get().isCamera(this)) cir.setReturnValue(true);
    }
}
