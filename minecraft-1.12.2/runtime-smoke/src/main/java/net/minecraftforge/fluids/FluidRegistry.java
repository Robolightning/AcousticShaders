package net.minecraftforge.fluids;
import java.util.*;import net.minecraft.block.Block;
public final class FluidRegistry { private static final Map<Block,Fluid> MAP=new IdentityHashMap<Block,Fluid>(); private FluidRegistry(){} public static void registerFluidBlock(Block block,Fluid fluid){MAP.put(block,fluid);} public static Fluid lookupFluidForBlock(Block block){return MAP.get(block);} }
