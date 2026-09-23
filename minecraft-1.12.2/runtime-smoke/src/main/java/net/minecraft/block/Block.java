package net.minecraft.block;
import java.util.*;import net.minecraft.util.*;import net.minecraft.block.state.IBlockState;import net.minecraft.world.World;import net.minecraft.util.math.*;
@SuppressWarnings("this-escape") public class Block{
 public static final java.util.List<Block> REGISTRY=new java.util.ArrayList<Block>();public static int collisionCalls;
 private final ResourceLocation id;public final SoundType blockSoundType;public float blockHardness=1.5f,blockResistance=10f;private IBlockState defaultState;private final java.util.Map<Integer,IBlockState> states=new java.util.HashMap<Integer,IBlockState>();private AxisAlignedBB collision=new AxisAlignedBB(0,0,0,1,1,1);private float filledPercentage=Float.NaN;
 public Block(String id,String soundId){this.id=new ResourceLocation(id);this.blockSoundType=new SoundType(soundId);REGISTRY.add(this);}
 public Block setCollision(AxisAlignedBB b){collision=b;return this;}public Block setFilledPercentage(float v){filledPercentage=v;return this;}public float getFilledPercentage(World world,BlockPos pos){return filledPercentage;}
 public ResourceLocation getRegistryName(){return id;}public SoundType getSoundType(IBlockState state,World world,BlockPos pos,Object entity){return blockSoundType;}
 public void setDefaultState(IBlockState s){if(defaultState==null)defaultState=s;}public void setState(int meta,IBlockState s){states.put(Integer.valueOf(meta&15),s);if(defaultState==null||meta==0)defaultState=s;}public IBlockState func_176223_P(){return defaultState;}public IBlockState func_176203_a(int meta){IBlockState s=states.get(Integer.valueOf(meta&15));return s==null?defaultState:s;}public int func_176201_c(IBlockState s){return s.meta()&15;}
 public void func_185477_a(IBlockState state,World world,BlockPos pos,AxisAlignedBB query,List<AxisAlignedBB> out,Object entity,boolean actual){collisionCalls++;if(collision!=null)out.add(collision.offset(pos));}
 public AxisAlignedBB collision(){return collision;}
}
