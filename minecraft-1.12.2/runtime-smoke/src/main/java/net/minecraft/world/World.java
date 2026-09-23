package net.minecraft.world;import net.minecraft.util.math.*;import net.minecraft.block.state.IBlockState;import net.minecraft.block.Block;import net.minecraft.block.material.Material;import net.minecraft.world.chunk.Chunk;
public final class World{
 public final java.util.List<net.minecraft.entity.Entity> field_72996_f=new java.util.ArrayList<net.minecraft.entity.Entity>();
 public final java.util.List<net.minecraft.entity.Entity> loadedEntityList=field_72996_f;
 public static int worldStateCalls,chunkLookupCalls;
 private final IBlockState air=new IBlockState(new Block("minecraft:air","block.stone.break").setCollision(null),Material.AIR,false);
 private final IBlockState stone=new IBlockState(new Block("minecraft:stone","block.stone.break"),Material.ROCK,true);
 private final IBlockState slab=new IBlockState(new Block("test:slab","block.stone.break").setCollision(new AxisAlignedBB(0,0,0,1,0.5,1)),Material.ROCK,false);
 private final Block waterBlock=new Block("minecraft:water","block.water.ambient").setCollision(null);
 private final IBlockState water=new IBlockState(waterBlock,Material.WATER,false,0);
 private final IBlockState flowingWater=new IBlockState(waterBlock,Material.WATER,false,4);
 private final IBlockState fallingWater=new IBlockState(waterBlock,Material.WATER,false,8);
 private final IBlockState lava=new IBlockState(new Block("minecraft:lava","block.lava.pop").setCollision(null),Material.LAVA,false);
 private final Block oilBlock=registryFluid(new Block("mod:oil","block.water.ambient").setCollision(null).setFilledPercentage(0.30f),new net.minecraftforge.fluids.Fluid("oil",850,300,false));
 private final IBlockState oil=new IBlockState(oilBlock,Material.AIR,false);
 private final IBlockState steam=new IBlockState(new net.minecraftforge.fluids.TestFluidBlock("mod:steam","block.fire.ambient",new net.minecraftforge.fluids.Fluid("steam",-100,420,true),-0.25f),Material.AIR,false);
 private final java.util.Map<Long,Chunk> chunks=new java.util.HashMap<Long,Chunk>();
 public IBlockState func_180495_p(BlockPos p){worldStateCalls++;return stateAt(p);}
 public Chunk func_72964_e(int x,int z){chunkLookupCalls++;long k=((long)x<<32)^(z&0xffffffffL);Chunk c=chunks.get(Long.valueOf(k));if(c==null){c=new Chunk(this);chunks.put(Long.valueOf(k),c);}return c;}
 private static Block registryFluid(Block block,net.minecraftforge.fluids.Fluid fluid){net.minecraftforge.fluids.FluidRegistry.registerFluidBlock(block,fluid);return block;}
 public IBlockState stateAt(BlockPos p){if(p.x==2&&p.y==2&&p.z==3)return steam;if(p.x==5&&p.y==2&&p.z==3)return fallingWater;if(p.x==2&&p.y==2&&p.z==2)return slab;if(p.x==3&&p.y==2&&p.z==2)return water;if(p.x==4&&p.y==2&&p.z==2)return lava;if(p.x==5&&p.y==2&&p.z==2)return flowingWater;if(p.x==1&&p.y==2&&p.z==2)return oil;boolean wall=Math.abs(p.x)>=6||Math.abs(p.z)>=6||p.y<=0||p.y>=6;return wall?stone:air;}
}
