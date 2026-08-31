package org.lwjgl;
import java.nio.ByteBuffer;
public final class PointerBuffer {private final long[] a;public PointerBuffer(int n){a=new long[n];}public PointerBuffer put(int i,long v){a[i]=v;return this;}public long get(int i){return a[i];}public static int getPointerSize(){return 8;}public static void put(ByteBuffer b,int i,long v){b.putLong(i,v);}}
