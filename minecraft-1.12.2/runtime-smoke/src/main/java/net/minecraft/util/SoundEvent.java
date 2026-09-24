package net.minecraft.util;
import java.util.HashMap;import java.util.Map;
import net.minecraftforge.registries.IForgeRegistryEntry;
public class SoundEvent implements IForgeRegistryEntry<SoundEvent>{
 public static final Registry REGISTRY=new Registry();public static final Registry field_187505_a=REGISTRY;
 private final ResourceLocation id;private ResourceLocation registryName;
 public SoundEvent(String id){this(new ResourceLocation(id));}
 public SoundEvent(ResourceLocation id){this.id=id;}
 public SoundEvent setRegistryName(ResourceLocation name){this.registryName=name;return this;}
 public ResourceLocation getRegistryName(){return registryName;}
 public ResourceLocation getSoundName(){return id;}
 public static final class Registry{private final Map<ResourceLocation,SoundEvent> values=new HashMap<ResourceLocation,SoundEvent>();public SoundEvent getObject(ResourceLocation id){return values.get(id);}public SoundEvent func_82594_a(ResourceLocation id){return getObject(id);}public void register(SoundEvent event){if(event.getRegistryName()==null)throw new IllegalStateException("missing registry name");values.put(event.getRegistryName(),event);}public void clear(){values.clear();}}
}
