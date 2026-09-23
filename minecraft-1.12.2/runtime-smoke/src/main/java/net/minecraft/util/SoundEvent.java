package net.minecraft.util;
import java.util.HashMap;import java.util.Map;
public class SoundEvent{
 public static final Registry REGISTRY=new Registry();public static final Registry field_187505_a=REGISTRY;
 private final ResourceLocation id;
 public SoundEvent(String id){this(new ResourceLocation(id));}
 public SoundEvent(ResourceLocation id){this.id=id;}
 public ResourceLocation getRegistryName(){return id;}
 public static final class Registry{private final Map<ResourceLocation,SoundEvent> values=new HashMap<ResourceLocation,SoundEvent>();public SoundEvent getObject(ResourceLocation id){SoundEvent e=values.get(id);if(e==null){e=new SoundEvent(id);values.put(id,e);}return e;}public SoundEvent func_82594_a(ResourceLocation id){return getObject(id);}}
}
