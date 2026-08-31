package net.minecraft.client;
import net.minecraft.client.audio.SoundHandler;import net.minecraft.client.gui.FontRenderer;import net.minecraft.client.gui.GuiScreen;
public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();public FontRenderer field_71466_p=new FontRenderer();private GuiScreen current;public static Minecraft func_71410_x(){return INSTANCE;}public void func_147108_a(GuiScreen screen){current=screen;}public SoundHandler func_147118_V(){return new SoundHandler();}public GuiScreen testCurrentScreen(){return current;} }
