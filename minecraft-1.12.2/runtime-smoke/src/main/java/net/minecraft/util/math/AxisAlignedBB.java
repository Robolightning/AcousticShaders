package net.minecraft.util.math;
public final class AxisAlignedBB{
 public final double minX,minY,minZ,maxX,maxY,maxZ;
 public AxisAlignedBB(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;}
 public AxisAlignedBB offset(BlockPos p){return new AxisAlignedBB(minX+p.x,minY+p.y,minZ+p.z,maxX+p.x,maxY+p.y,maxZ+p.z);}
}
