package com.sun.jna;
public class NativeLibrary {
    public static NativeLibrary getInstance(String name){return new NativeLibrary();}
    public Function getFunction(String name){return new Function();}
    public String getName(){return "stub";}
}
