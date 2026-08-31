package dev.acoustic.mc1122.mixin

import dev.acoustic.mc1122.forge.LegacySoundHook
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import paulscode.sound.Channel

/** Minimal Paulscode-only hook to avoid transformer collisions with OptiFine and most Minecraft ASM mods. */
@Mixin(targets = ["paulscode.sound.libraries.SourceLWJGLOpenAL"], remap = false)
abstract class MixinSourceLWJGLOpenAL {
    @Inject(method = ["play(Lpaulscode/sound/Channel;)V"], at = [At("TAIL")], remap = false)
    private fun acousticShadersAfterPlay(channel: Channel, ci: CallbackInfo) { LegacySoundHook.onSourcePlay(this) }
    @Inject(method = ["positionChanged()V"], at = [At("TAIL")], remap = false)
    private fun acousticShadersAfterPositionChanged(ci: CallbackInfo) { LegacySoundHook.onSourcePositionChanged(this) }
    @Inject(method = ["cleanup()V"], at = [At("HEAD")], remap = false)
    private fun acousticShadersBeforeCleanup(ci: CallbackInfo) { LegacySoundHook.onSourceCleanup(this) }
}
