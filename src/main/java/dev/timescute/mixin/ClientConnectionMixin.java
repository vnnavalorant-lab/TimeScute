package dev.timescute.mixin;

import dev.timescute.client.RecordingManager;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vê cada pacote que chega do servidor (inclusive no modo um jogador), antes de ser processado. */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"))
    private void timescute$record(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        ClientConnection self = (ClientConnection) (Object) this;
        RecordingManager.get().onClientPacket(self, packet, self.getPacketListener());
    }
}
