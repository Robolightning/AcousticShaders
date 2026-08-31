package dev.acoustic.mc1122.forge

/** Minimal release logger with a runtime-configurable debug channel. */
object AcousticLog {
    @Volatile private var debugEnabledValue = false
    @JvmStatic fun setDebug(enabled: Boolean) { debugEnabledValue = enabled }
    @JvmStatic fun debugEnabled(): Boolean = debugEnabledValue
    @JvmStatic fun info(message: String) { println("[AcousticShaders] $message") }
    @JvmStatic fun debug(message: String) { if (debugEnabledValue) println("[AcousticShaders][debug] $message") }
    @JvmStatic fun warn(message: String) { System.err.println("[AcousticShaders][warn] $message") }
    @JvmStatic fun error(message: String, t: Throwable?) {
        System.err.println("[AcousticShaders][error] $message" + if (t == null) "" else " : ${shortMessage(t)}")
        if (debugEnabledValue && t != null) t.printStackTrace(System.err)
    }
    private fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName
}
