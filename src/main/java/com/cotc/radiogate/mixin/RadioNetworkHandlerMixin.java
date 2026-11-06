package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.RadioNetworkHandler;
import be.n2styles.project_radio.RadioPayload;
import be.n2styles.project_radio.SpeakerPayload;
import com.cotc.radiogate.RadioGate;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two packets a client can send to the server. They arrive on the network thread: the real work is moved to the server thread
 * ({@code enqueueWork}), checked by Radio Gate, and only then given to Project Radio's own handler (which marks itself via
 * {@link RadioGate#PASS}).
 */
@Mixin(value = RadioNetworkHandler.class, remap = false)
public class RadioNetworkHandlerMixin {
    @Inject(method = "handleRadioData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void radiogate$gateRadio(RadioPayload payload, IPayloadContext ctx, CallbackInfo ci) {
        if (!ctx.flow().isServerbound() || RadioGate.PASS.get() != null) {
            return;
        }
        ci.cancel();
        ctx.enqueueWork(() -> RadioGate.handleRadio(payload, ctx));
    }

    @Inject(method = "handleSpeakerData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void radiogate$gateSpeaker(SpeakerPayload payload, IPayloadContext ctx, CallbackInfo ci) {
        if (!ctx.flow().isServerbound() || RadioGate.PASS.get() != null) {
            return;
        }
        ci.cancel();
        ctx.enqueueWork(() -> RadioGate.handleSpeaker(payload, ctx));
    }
}
