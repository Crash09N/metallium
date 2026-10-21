package de.nprmedia.nativium.compat.voxy.mixin;

import de.nprmedia.nativium.compat.voxy.VoxyCompat;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows Voxy's "disabled on Metal" notice once, on the first world join (no-op when Voxy is absent). */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void nativium$voxyNotice(final ClientboundLoginPacket packet, final CallbackInfo ci) {
        VoxyCompat.showNoticeOnce();
    }
}
