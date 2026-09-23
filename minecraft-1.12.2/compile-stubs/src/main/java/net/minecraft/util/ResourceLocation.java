package net.minecraft.util;
public class ResourceLocation {
    private final String value;
    public ResourceLocation(String value){this.value=value;}
    public ResourceLocation(String namespace,String path){this.value=namespace+":"+path;}
    @Override public String toString(){return value;}
    @Override public int hashCode(){return value.hashCode();}
    @Override public boolean equals(Object o){return o instanceof ResourceLocation && value.equals(o.toString());}
}
