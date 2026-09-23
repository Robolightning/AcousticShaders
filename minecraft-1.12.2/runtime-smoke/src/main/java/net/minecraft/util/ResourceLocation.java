package net.minecraft.util;
public final class ResourceLocation{
 private final String v;
 public ResourceLocation(String v){this.v=v;}
 public ResourceLocation(String namespace,String path){this.v=namespace+":"+path;}
 @Override public String toString(){return v;}
 @Override public int hashCode(){return v.hashCode();}
 @Override public boolean equals(Object o){return o instanceof ResourceLocation&&v.equals(o.toString());}
}
