package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.RadioNetworkHandler;
import be.n2styles.project_radio.RadioPayload;
import be.n2styles.project_radio.SpeakerPayload;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Static access to Project Radio's private handlers and to its map of running portable radios. */
@Mixin(value = RadioNetworkHandler.class, remap = false)
public interface RadioNetworkHandlerAccessor {
    @Accessor(value = "activePortableRadios", remap = false)
    static ConcurrentHashMap<UUID, RadioPayload> radiogate$portables() {
        throw new AssertionError();
    }

    @Invoker(value = "handleRadioData", remap = false)
    static void radiogate$callRadio(RadioPayload payload, IPayloadContext ctx) {
        throw new AssertionError();
    }

    @Invoker(value = "handleSpeakerData", remap = false)
    static void radiogate$callSpeaker(SpeakerPayload payload, IPayloadContext ctx) {
        throw new AssertionError();
    }
}
