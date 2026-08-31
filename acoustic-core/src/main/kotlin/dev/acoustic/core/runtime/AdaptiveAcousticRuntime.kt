package dev.acoustic.core.runtime

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.quality.AdaptiveBudgetController
import java.util.ArrayList

/** Profile-level adaptive runtime. Fine-grained pass budgets can be layered underneath later. */
class AdaptiveAcousticRuntime(private val pack: LoadedShaderPack, targetMillis: Double, private val workers: Int, initial: String) : AutoCloseable {
    private val profiles = orderedProfiles(pack)
    private val controller: AdaptiveBudgetController
    private var session: AcousticRuntimeSession
    private var activeIndex: Int
    init { require(profiles.isNotEmpty()) { "no profiles" }; activeIndex = profiles.indexOf(initial); if (activeIndex < 0) throw IllegalArgumentException("unknown initial profile: $initial"); controller = AdaptiveBudgetController(targetMillis,0,profiles.size-1,activeIndex); session = AcousticRuntimeSession(pack,profiles[activeIndex],workers) }
    fun currentProfile(): String = profiles[activeIndex]
    @Throws(Exception::class) fun process(scene: AcousticScene, source: Vec3, listener: Vec3): AcousticRuntimeSession.FrameResult { val frame=session.process(scene,source,listener); val next=controller.sample(frame.report().elapsedNanos()/1_000_000.0); if(next!=activeIndex){session.close();activeIndex=next;session=AcousticRuntimeSession(pack,profiles[activeIndex],workers)}; return frame }
    fun smoothedMillis(): Double = controller.smoothedMillis()
    override fun close() { session.close() }
    companion object {
        private val preferred = listOf("POTATO","LOW","MEDIUM","BALANCED","HIGH","ULTRA","EXTREME","CINEMATIC")
        private fun orderedProfiles(pack: LoadedShaderPack): List<String> { val out=ArrayList<String>(); for(n in preferred) if(pack.options().profiles().contains(n)) out.add(n); for(n in pack.options().profiles()) if(!out.contains(n)) out.add(n); return out }
    }
}
