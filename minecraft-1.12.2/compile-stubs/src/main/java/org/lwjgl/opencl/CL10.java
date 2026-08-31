package org.lwjgl.opencl;
import java.nio.ByteBuffer;import java.nio.FloatBuffer;import java.nio.IntBuffer;import org.lwjgl.PointerBuffer;
public final class CL10 {
 private CL10(){}
 public static final int CL_SUCCESS=0,CL_TRUE=1;
 public static final int CL_DEVICE_TYPE_GPU=4;
 public static final int CL_DEVICE_MAX_COMPUTE_UNITS=0x1002,CL_DEVICE_MAX_CLOCK_FREQUENCY=0x100C,CL_DEVICE_AVAILABLE=0x1027,CL_DEVICE_COMPILER_AVAILABLE=0x1028,CL_DEVICE_NAME=0x102B,CL_DEVICE_VENDOR=0x102C,CL_PROGRAM_BUILD_LOG=0x1183;
 public static final int CL_MEM_READ_WRITE=1,CL_MEM_WRITE_ONLY=2,CL_MEM_READ_ONLY=4,CL_MEM_COPY_HOST_PTR=32;
 public static CLCommandQueue clCreateCommandQueue(CLContext c,CLDevice d,long p,IntBuffer e){return new CLCommandQueue();}
 public static CLMem clCreateBuffer(CLContext c,long f,long size,IntBuffer e){return new CLMem();}
 public static CLMem clCreateBuffer(CLContext c,long f,FloatBuffer h,IntBuffer e){return new CLMem();}
 public static CLMem clCreateBuffer(CLContext c,long f,IntBuffer h,IntBuffer e){return new CLMem();}
 public static CLProgram clCreateProgramWithSource(CLContext c,CharSequence s,IntBuffer e){return new CLProgram();}
 public static int clBuildProgram(CLProgram p,CLDevice d,CharSequence o,CLBuildProgramCallback cb){return CL_SUCCESS;}
 public static CLKernel clCreateKernel(CLProgram p,CharSequence n,IntBuffer e){return new CLKernel();}
 public static int clSetKernelArg(CLKernel k,int i,CLObject o){return CL_SUCCESS;}
 public static int clSetKernelArg(CLKernel k,int i,ByteBuffer v){return CL_SUCCESS;}
 public static int clSetKernelArg(CLKernel k,int i,IntBuffer v){return CL_SUCCESS;}
 public static int clSetKernelArg(CLKernel k,int i,FloatBuffer v){return CL_SUCCESS;}
 public static int clEnqueueNDRangeKernel(CLCommandQueue q,CLKernel k,int d,PointerBuffer off,PointerBuffer global,PointerBuffer local,PointerBuffer waits,PointerBuffer event){return CL_SUCCESS;}
 public static int clEnqueueReadBuffer(CLCommandQueue q,CLMem m,int blocking,long offset,FloatBuffer ptr,PointerBuffer waits,PointerBuffer event){return CL_SUCCESS;}
 public static int clEnqueueWriteBuffer(CLCommandQueue q,CLMem m,int blocking,long offset,FloatBuffer ptr,PointerBuffer waits,PointerBuffer event){return CL_SUCCESS;}
 public static int clEnqueueWriteBuffer(CLCommandQueue q,CLMem m,int blocking,long offset,IntBuffer ptr,PointerBuffer waits,PointerBuffer event){return CL_SUCCESS;}
 public static int clFinish(CLCommandQueue q){return CL_SUCCESS;}
 public static int clReleaseMemObject(CLMem m){return CL_SUCCESS;}public static int clReleaseKernel(CLKernel k){return CL_SUCCESS;}public static int clReleaseProgram(CLProgram p){return CL_SUCCESS;}public static int clReleaseCommandQueue(CLCommandQueue q){return CL_SUCCESS;}public static int clReleaseContext(CLContext c){return CL_SUCCESS;}
}
