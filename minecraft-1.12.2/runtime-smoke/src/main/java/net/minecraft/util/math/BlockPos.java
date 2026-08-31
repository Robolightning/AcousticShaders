package net.minecraft.util.math;
public class BlockPos{public int x,y,z;public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}public int func_177958_n(){return x;}public int func_177956_o(){return y;}public int func_177952_p(){return z;}
 public static final class MutableBlockPos extends BlockPos{public MutableBlockPos(){super(0,0,0);}public MutableBlockPos func_181079_c(int x,int y,int z){this.x=x;this.y=y;this.z=z;return this;}}
}
