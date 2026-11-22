package dev.timescute.mixin;

import dev.timescute.client.RenderSession;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Em um jogador, durante a renderização o servidor para: o mundo fica parado e o vídeo sai exato. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void timescute$freeze(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (RenderSession.get().shouldFreezeServer()) ci.cancel();
    }
}
