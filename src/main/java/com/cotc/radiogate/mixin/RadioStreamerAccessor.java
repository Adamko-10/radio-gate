package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.RadioStreamer;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Client: the table of running streams, so a revoke can stop them. */
@Mixin(value = RadioStreamer.class, remap = false)
public interface RadioStreamerAccessor {
    @Accessor(value = "activeRadios", remap = false)
    static ConcurrentHashMap<String, RadioStreamer.RadioInstance> radiogate$activeRadios() {
        throw new AssertionError();
    }
}
