package dev.timescute.mixin;

import dev.timescute.client.ActorManager;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A pele de um ator vem da lista de jogadores (a sua, ou a de um jogador online escolhido pelo nome). */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Inject(method = "getPlayerListEntry", at = @At("HEAD"), cancellable = true)
    private void timescute$skin(CallbackInfoReturnable<PlayerListEntry> cir) {
        PlayerListEntry e = ActorManager.get().skinEntry(this);
        if (e != null) cir.setReturnValue(e);
    }
}
