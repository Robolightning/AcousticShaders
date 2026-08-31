package com.sun.jna;
public class Pointer {
    public void setInt(long offset,int value){}
    public void setLong(long offset,long value){}
    public void setFloat(long offset,float value){}
    public void setPointer(long offset,Pointer value){}
    public void setMemory(long offset,long length,byte value){}
    public void write(long offset,byte[] data,int index,int length){}
    public void write(long offset,int[] data,int index,int length){}
    public void write(long offset,float[] data,int index,int length){}
    public void read(long offset,byte[] data,int index,int length){}
    public void read(long offset,int[] data,int index,int length){}
    public void read(long offset,float[] data,int index,int length){}
    public byte[] getByteArray(long offset,int length){return new byte[length];}
    public String getString(long offset){return "";}
}
