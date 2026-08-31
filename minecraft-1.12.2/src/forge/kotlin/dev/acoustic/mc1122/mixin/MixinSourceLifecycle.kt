package dev.acoustic.mc1122.mixin

import dev.acoustic.mc1122.forge.LegacySoundHook
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/** Removes stopped/culled sources immediately instead of waiting for eventual cleanup. */
@Mixin(targets = ["paulscode.sound.Source"], remap = false)
abstract class MixinSourceLifecycle {
    @Inject(method = ["stop()V"], at = [At("HEAD")], remap = false)
    private fun acousticShadersBeforeStop(ci: CallbackInfo) { LegacySoundHook.onSourceCleanup(this) }
    @Inject(method = ["cull()V"], at = [At("HEAD")], remap = false)
    private fun acousticShadersBeforeCull(ci: CallbackInfo) { LegacySoundHook.onSourceCleanup(this) }
}
