package net.minecraftforge.registries;
import net.minecraft.util.ResourceLocation;
public interface IForgeRegistryEntry<T extends IForgeRegistryEntry<T>> {
 T setRegistryName(ResourceLocation name);
 ResourceLocation getRegistryName();
}
