package net.minecraftforge.event;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;
public class RegistryEvent<T extends IForgeRegistryEntry<T>> {
 public static final class Register<T extends IForgeRegistryEntry<T>> extends RegistryEvent<T> {
  private final IForgeRegistry<T> registry;
  public Register(IForgeRegistry<T> registry){this.registry=registry;}
  public IForgeRegistry<T> getRegistry(){return registry;}
 }
}
