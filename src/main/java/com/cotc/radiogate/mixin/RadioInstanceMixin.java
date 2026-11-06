package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.RadioStreamer;
import com.cotc.radiogate.client.ClientGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client: the thread that opens the connection starts here, so even an instance made some other way cannot connect. */
@Mixin(value = RadioStreamer.RadioInstance.class, remap = false)
public class RadioInstanceMixin {
    @Inject(method = "start", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiogate$checkStart(CallbackInfo ci) {
        RadioStreamer.RadioInstance self = (RadioStreamer.RadioInstance) (Object) this;
        if (!ClientGate.allowed(self.url)) {
            ClientGate.refused(self.url);
            self.stop();
            ci.cancel();
        }
    }
}
