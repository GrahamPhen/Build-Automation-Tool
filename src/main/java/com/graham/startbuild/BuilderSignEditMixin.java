package com.graham.startbuild;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Decorative signs are placed normally, but their text editor must not interrupt the recorded build. */
@Mixin(LocalPlayer.class)
abstract class BuilderSignEditMixin {
    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    private void startbuild$skipPlacedSignEditor(SignBlockEntity sign, boolean front, CallbackInfo ci) {
        if (StartBuildMod.suppressBuilderSignEditor(sign.getBlockPos())) ci.cancel();
    }
}
