package dev.timescute.mixin;

import dev.timescute.client.RenderSession;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Durante a renderização, cada quadro avança uma fração FIXA de tick (em vez de depender do relógio real).
 * É isso que deixa o vídeo suave e determinístico, não importa quanto tempo cada quadro demore para ser feito.
 */
@Mixin(RenderTickCounter.class)
public abstract class RenderTickCounterMixin {
    @Shadow public float lastFrameDuration;
    @Shadow public float tickDelta;
    @Shadow private long prevTimeMillis;

    @Inject(method = "beginRenderTick", at = @At("HEAD"), cancellable = true)
    private void timescute$fixedStep(long timeMillis, CallbackInfoReturnable<Integer> cir) {
        float advance = RenderSession.get().tickAdvance();
        if (Float.isNaN(advance)) return; // fora da renderização: comportamento normal
        prevTimeMillis = timeMillis;      // evita um salto enorme quando a renderização acabar
        lastFrameDuration = advance;
        tickDelta += advance;
        int ticks = (int) tickDelta;
        tickDelta -= ticks;
        cir.setReturnValue(ticks);
    }
}
