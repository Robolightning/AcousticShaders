package dev.acoustic.mc1122.forge;
import java.lang.reflect.*;import net.minecraft.client.Minecraft;import net.minecraft.client.audio.MovingSound;import net.minecraft.entity.Entity;import net.minecraft.entity.projectile.*;import net.minecraft.world.World;
public final class ProjectileEmitterSmokeTest {
 private static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 private static Object manager()throws Exception{Constructor<?> c=Class.forName("dev.acoustic.mc1122.forge.LegacyProjectileEmitterManager").getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
 private static void tick(Object m,World w,Entity l)throws Exception{Method x=m.getClass().getDeclaredMethod("tick",Object.class,Object.class);x.setAccessible(true);x.invoke(m,w,l);}
 private static EntityTippedArrow arrow(double x){EntityTippedArrow a=new EntityTippedArrow(x,1,0);a.field_70159_w=1.0;return a;}
 public static void main(String[]args)throws Exception{
  Minecraft mc=Minecraft.getMinecraft();mc.soundHandler.clear();World w=new World();Entity listener=new Entity(0,1,0);Object m=manager();
  EntityTippedArrow a=arrow(2);w.loadedEntityList.add(a);tick(m,w,listener);check(mc.soundHandler.played.size()==1,"create");MovingSound s=(MovingSound)mc.soundHandler.played.get(0);check(Math.abs(s.xPosF-2f)<0.001,"initial pos");
  a.field_70165_t=5;tick(m,w,listener);check(Math.abs(s.xPosF-5f)<0.001,"follow");
  a.field_70254_i=true;tick(m,w,listener);check(s.donePlaying,"inGround cleanup");
  mc.soundHandler.clear();w.loadedEntityList.clear();EntityTippedArrow dead=arrow(2);dead.field_70128_L=true;w.loadedEntityList.add(dead);tick(m,w,listener);check(mc.soundHandler.played.isEmpty(),"dead excluded");
  w.loadedEntityList.clear();for(int i=0;i<30;i++){EntityTippedArrow q=arrow(1+i);w.loadedEntityList.add(q);}tick(m,w,listener);check(mc.soundHandler.played.size()==24,"nearest-24 bound");
  mc.soundHandler.clear();w.loadedEntityList.clear();mod.projectile.ModdedArrow mod=new mod.projectile.ModdedArrow(2,1,0);mod.field_70159_w=1;w.loadedEntityList.add(mod);tick(m,w,listener);check(mc.soundHandler.played.isEmpty(),"modded no duplicate");
  mc.soundHandler.clear();w.loadedEntityList.clear();EntityThrowable t=new EntityThrowable(2,1,0);t.field_70159_w=1;EntityFireball f=new EntityFireball(3,1,0);f.field_70159_w=1;EntityLlamaSpit l=new EntityLlamaSpit(4,1,0);l.field_70159_w=1;EntityShulkerBullet b=new EntityShulkerBullet(5,1,0);b.field_70159_w=1;w.loadedEntityList.add(t);w.loadedEntityList.add(f);w.loadedEntityList.add(l);w.loadedEntityList.add(b);tick(m,w,listener);check(mc.soundHandler.played.size()==4,"all projectile families");
  tick(m,null,null);for(net.minecraft.client.audio.ISound q:mc.soundHandler.played)check(((MovingSound)q).donePlaying,"world cleanup");
  System.out.println("PASS: projectile emitter create/follow/inGround/death/bound/world-cleanup/modded-no-duplicate + projectile families");
 }
}
