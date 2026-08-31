package org.lwjgl.opencl; import org.lwjgl.LWJGLException;
public final class CL {private static boolean c;private CL(){}public static void create()throws LWJGLException{c=true;}public static boolean isCreated(){return c;}public static void destroy(){c=false;}}
