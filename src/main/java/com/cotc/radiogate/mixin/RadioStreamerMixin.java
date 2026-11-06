package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.RadioStreamer;
import com.cotc.radiogate.client.ClientGate;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client: the one method every start goes through (radio packet, block entity load, Walkman, seek bar). */
@Mixin(value = RadioStreamer.class, remap = false)
public class RadioStreamerMixin {
    @Inject(method = "playAt", at = @At("HEAD"), cancellable = true, remap = false)
    private static void radiogate$checkPlayAt(String urlString, BlockPos pos, UUID owner, int frequency, float pct, CallbackInfo ci) {
        if (!ClientGate.allowed(urlString)) {
            ClientGate.refused(urlString);
            RadioStreamer.stop(pos, owner, false);
            ci.cancel();
        }
    }
}
