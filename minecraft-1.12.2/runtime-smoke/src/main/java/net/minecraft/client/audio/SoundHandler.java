package net.minecraft.client.audio;
import java.util.ArrayList;import java.util.List;
public class SoundHandler {
 public final List<ISound> played=new ArrayList<ISound>();
 public void playSound(ISound sound){played.add(sound);} public void func_147682_a(ISound sound){playSound(sound);}
 public void clear(){played.clear();}
}
