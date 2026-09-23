package net.minecraftforge.fluids;
import net.minecraft.world.World;import net.minecraft.util.math.BlockPos;
public interface IFluidBlock { Fluid getFluid(); float getFilledPercentage(World world,BlockPos pos); }
