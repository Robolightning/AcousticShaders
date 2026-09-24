package org.lwjgl.openal;
import java.nio.ByteBuffer;import java.util.*;
public final class AL10{
 public static final int AL_VELOCITY=0x1006,AL_SOURCE_STATE=0x1010,AL_INITIAL=0x1011,AL_PLAYING=0x1012,AL_PAUSED=0x1013,AL_STOPPED=0x1014,AL_BUFFER=0x1009,AL_GAIN=0x100A,AL_FORMAT_STEREO16=0x1103;
 public static int sourceiCalls,source3fCalls,lastSource,lastParam,lastValue,sourcePlayCalls,sourceStopCalls,deleteSourceCalls,deleteBufferCalls,bufferDataCalls;public static float lastX,lastY,lastZ;private static int nextBuffer=500,nextSource=700;
 public static final Map<Integer,Integer> STATES=new HashMap<Integer,Integer>();public static final Map<Integer,byte[]> BUFFERS=new HashMap<Integer,byte[]>();public static final Map<Integer,float[]> VELOCITIES=new HashMap<Integer,float[]>();
 public static void alSourcei(int source,int param,int value){sourceiCalls++;lastSource=source;lastParam=param;lastValue=value;if(param==AL_BUFFER&&value!=0)STATES.put(source,AL_INITIAL);}
 public static void alSource3f(int source,int param,float x,float y,float z){source3fCalls++;lastSource=source;lastParam=param;lastX=x;lastY=y;lastZ=z;if(param==AL_VELOCITY)VELOCITIES.put(source,new float[]{x,y,z});}
 public static void alSourcef(int source,int param,float value){}
 public static int alGetSourcei(int source,int param){if(param==AL_SOURCE_STATE)return STATES.containsKey(source)?STATES.get(source):AL_PLAYING;return 0;}
 public static float alGetSourcef(int source,int param){return param==AL11.AL_SEC_OFFSET?0.125f:0f;}
 public static int alGenBuffers(){return nextBuffer++;}public static int alGenSources(){return nextSource++;}
 public static void alBufferData(int buffer,int format,ByteBuffer data,int rate){ByteBuffer d=data.duplicate();byte[] b=new byte[d.remaining()];d.get(b);BUFFERS.put(buffer,b);bufferDataCalls++;}
 public static void alSourcePlay(int source){STATES.put(source,AL_PLAYING);sourcePlayCalls++;}public static void alSourcePause(int source){STATES.put(source,AL_PAUSED);}public static void alSourceStop(int source){STATES.put(source,AL_STOPPED);sourceStopCalls++;}
 public static void alDeleteSources(int source){STATES.remove(source);deleteSourceCalls++;}public static void alDeleteBuffers(int buffer){BUFFERS.remove(buffer);deleteBufferCalls++;}
 public static int alGetError(){return 0;}
}
