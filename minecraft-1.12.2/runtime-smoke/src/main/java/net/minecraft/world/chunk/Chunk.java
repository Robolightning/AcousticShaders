package net.minecraft.world.chunk;import net.minecraft.world.World;import net.minecraft.util.math.BlockPos;import net.minecraft.block.state.IBlockState;
public final class Chunk{public static int stateCalls;private final World world;public Chunk(World world){this.world=world;}public IBlockState func_177435_g(BlockPos pos){stateCalls++;return world.stateAt(pos);}}
