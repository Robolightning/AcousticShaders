package net.minecraftforge.registries;
public interface IForgeRegistry<T extends IForgeRegistryEntry<T>> {
 void register(T value);
}
