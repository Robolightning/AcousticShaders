package paulscode.sound;
import java.nio.IntBuffer;
public class Channel {
 public final IntBuffer ALSource=IntBuffer.allocate(1);
 public int playCalls;
 public int pauseCalls;
 public Channel(){}
 public Channel(int sourceId){ALSource.put(0,sourceId);}
 public void play(){playCalls++;}
 public void pause(){pauseCalls++;}
}
