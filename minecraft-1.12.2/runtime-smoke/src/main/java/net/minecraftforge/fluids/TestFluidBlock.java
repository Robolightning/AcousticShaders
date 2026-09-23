package net.minecraftforge.fluids;
import net.minecraft.block.Block;import net.minecraft.world.World;import net.minecraft.util.math.BlockPos;
public final class TestFluidBlock extends Block implements IFluidBlock { private final Fluid fluid; private final float fill; public TestFluidBlock(String id,String soundId,Fluid fluid,float fill){super(id,soundId);this.fluid=fluid;this.fill=fill;setCollision(null);} public Fluid getFluid(){return fluid;} @Override public float getFilledPercentage(World world,BlockPos pos){return fill;} }
