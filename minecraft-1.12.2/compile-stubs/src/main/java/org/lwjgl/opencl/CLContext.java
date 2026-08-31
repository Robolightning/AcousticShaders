package org.lwjgl.opencl; import java.nio.IntBuffer;import java.util.List;import org.lwjgl.LWJGLException;
public final class CLContext extends CLObject {public static CLContext create(CLPlatform platform,List<CLDevice> devices,IntBuffer err)throws LWJGLException{return new CLContext();}}
