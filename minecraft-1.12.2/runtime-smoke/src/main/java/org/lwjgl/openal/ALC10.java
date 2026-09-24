package org.lwjgl.openal;
public final class ALC10 {
    public static ALCcontext CURRENT = new ALCcontext();
    private static final ALCdevice D = new ALCdevice();
    public static boolean EFX_PRESENT = true;
    public static ALCcontext alcGetCurrentContext() { return CURRENT; }
    public static ALCdevice alcGetContextsDevice(ALCcontext c) { return D; }
    public static boolean alcIsExtensionPresent(ALCdevice d, String s) { return "ALC_EXT_EFX".equals(s) && EFX_PRESENT; }
}
