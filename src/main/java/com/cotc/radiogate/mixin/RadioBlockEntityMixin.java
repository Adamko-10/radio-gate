package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.RadioBlockEntity;
import com.cotc.radiogate.RadioGate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block entity sync to a client (chunk packets and update packets both call getUpdateTag) loses its link
 * unless the server approves it, so a pre-filled radio (NBT, creative copy, dispenser, structure) never starts on a client.
 */
@Mixin(value = RadioBlockEntity.class, remap = false)
public class RadioBlockEntityMixin {
    @Inject(method = "getUpdateTag", at = @At("RETURN"), remap = false)
    private void radiogate$cleanUpdateTag(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        if (tag == null) {
            return;
        }
        String url = tag.getString("url");
        if (!url.isEmpty() && !RadioGate.playbackAllowed(url)) {
            tag.putString("url", "");
            tag.putBoolean("isPlaying", false);
        }
    }
}
