package net.minecraft.client;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.resources.ResourcePackRepository;
public final class Minecraft{
    private static final Minecraft INSTANCE=new Minecraft();
    public World field_71441_e;
    public Entity field_71439_g;
    public net.minecraft.client.gui.GuiScreen currentScreen;
    public net.minecraft.client.gui.FontRenderer fontRenderer=new net.minecraft.client.gui.FontRenderer();
    public final GameSettings gameSettings=new GameSettings();
    public final GameSettings field_71474_y=gameSettings;
    public final ResourcePackRepository resourcePackRepository=new ResourcePackRepository();
    public int resourceRefreshes;
    private Minecraft(){}
    public static Minecraft func_71410_x(){return INSTANCE;}
    public static Minecraft getMinecraft(){return INSTANCE;}
    public Entity func_175606_aa(){return field_71439_g;}
    public void displayGuiScreen(net.minecraft.client.gui.GuiScreen s){currentScreen=s;}
    public net.minecraft.client.audio.SoundHandler getSoundHandler(){return new net.minecraft.client.audio.SoundHandler();}
    public ResourcePackRepository getResourcePackRepository(){return resourcePackRepository;}
    public ResourcePackRepository func_110438_M(){return resourcePackRepository;}
    public void refreshResources(){resourceRefreshes++;}
    public void func_110436_a(){refreshResources();}
}
