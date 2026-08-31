package com.sun.jna.ptr;
import com.sun.jna.Pointer;
public class PointerByReference { private Pointer value; public PointerByReference(){} public PointerByReference(Pointer value){this.value=value;} public Pointer getValue(){return value;} public void setValue(Pointer value){this.value=value;} }
