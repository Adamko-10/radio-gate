package com.cotc.radiogate.mixin;

import be.n2styles.project_radio.MusicScanner;
import com.cotc.radiogate.client.ClientGate;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client: the Walkman sees no local music (the folder is not even listed or created). */
@Mixin(value = MusicScanner.class, remap = false)
public class MusicScannerMixin {
    @Inject(method = "getMp3Files", at = @At("HEAD"), cancellable = true, remap = false)
    private static void radiogate$noLocalFiles(CallbackInfoReturnable<List<File>> cir) {
        if (!ClientGate.localFilesAllowed()) {
            cir.setReturnValue(new ArrayList<>());
        }
    }
}
