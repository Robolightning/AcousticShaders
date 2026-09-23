package net.minecraft.client.audio;
import net.minecraft.util.SoundCategory;import net.minecraft.util.SoundEvent;
public abstract class MovingSound implements ISound {
 protected final SoundEvent sound; protected final SoundCategory category;
 public float xPosF,yPosF,zPosF,volume=1f,pitch=1f; public boolean repeat,donePlaying; public int repeatDelay; public AttenuationType attenuationType=AttenuationType.LINEAR;
 protected MovingSound(SoundEvent sound,SoundCategory category){this.sound=sound;this.category=category;}
 public abstract void update();
}
