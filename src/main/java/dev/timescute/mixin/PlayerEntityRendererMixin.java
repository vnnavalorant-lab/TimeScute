package dev.timescute.mixin;

import dev.timescute.client.ActorManager;
import dev.timescute.client.ActorModelRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Atores com modelo do Blockbench: desenha o modelo dele no lugar do jogador. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), cancellable = true)
    private void timescute$customModel(AbstractClientPlayerEntity player, float yaw, float tickDelta, MatrixStack matrices,
                                       VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        ActorManager.ModelView mv = ActorManager.get().modelView(player);
        if (mv == null) return;
        ActorModelRenderer.render(player, tickDelta, matrices, vcp, light, mv);
        ci.cancel();
    }
}
