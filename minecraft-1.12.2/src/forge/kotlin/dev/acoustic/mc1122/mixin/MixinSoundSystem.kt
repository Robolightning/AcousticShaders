package dev.acoustic.mc1122.mixin

import dev.acoustic.mc1122.forge.LegacySoundHook
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import paulscode.sound.CommandObject

/** Drains completed acoustic work only while Paulscode owns the OpenAL context. */
@Mixin(targets = ["paulscode.sound.SoundSystem"], remap = false)
abstract class MixinSoundSystem {
    @Inject(method = ["CommandQueue(Lpaulscode/sound/CommandObject;)Z"], at = [At("RETURN")], remap = false)
    private fun acousticShadersAfterCommandQueue(command: CommandObject?, cir: CallbackInfoReturnable<Boolean>) { if (command == null) LegacySoundHook.onAudioCommandTick() }
}
