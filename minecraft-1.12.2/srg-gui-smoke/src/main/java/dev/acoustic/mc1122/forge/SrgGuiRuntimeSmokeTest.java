package dev.acoustic.mc1122.forge;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Executes the release GUI against SRG-only Minecraft members, matching a production Forge 1.12.2 client. */
public final class SrgGuiRuntimeSmokeTest {
    private SrgGuiRuntimeSmokeTest() {}
    public static void main(String[] args) throws Exception {
        AcousticShadersForgeMod mod=new AcousticShadersForgeMod();
        mod.preInit(new FMLPreInitializationEvent());
        GuiScreenOptionsSounds sounds=new GuiScreenOptionsSounds();
        List<GuiButton> buttons=sounds.testButtons();
        int vanillaCount=buttons.size();
        mod.onSoundOptionsInit(new GuiScreenEvent.InitGuiEvent.Post(sounds,buttons));
        if(buttons.size()!=vanillaCount+1)throw new AssertionError("Music & Sounds button was not inserted exactly once: "+buttons.size());
        GuiButton open=find(buttons,0xAC51),subtitles=find(buttons,201),done=find(buttons,200);
        if(open==null||subtitles==null||done==null)throw new AssertionError("required Music & Sounds controls missing");
        assertNoOverlap(subtitles,open,"Subtitles/Acoustic Shaders");
        assertNoOverlap(open,done,"Acoustic Shaders/Done");
        if(done.field_146129_i+done.field_146121_g>sounds.field_146295_m)throw new AssertionError("Done button extends past screen bottom");
        mod.onSoundOptionsInit(new GuiScreenEvent.InitGuiEvent.Post(sounds,buttons));
        if(buttons.size()!=vanillaCount+1)throw new AssertionError("duplicate Music & Sounds button inserted");
        GuiScreenEvent.ActionPerformedEvent.Pre action=new GuiScreenEvent.ActionPerformedEvent.Pre(sounds,open,buttons);
        mod.onSoundOptionsAction(action);
        if(!action.isCanceled())throw new AssertionError("Acoustic Shaders action was not canceled");
        GuiScreen current=Minecraft.func_71410_x().testCurrentScreen();
        if(current==null||!current.getClass().getName().endsWith("GuiAcousticShaders"))throw new AssertionError("selector did not open");
        current.func_73866_w_();
        current.func_73863_a(30,80,0.0F);
        if(current.testButtons().isEmpty())throw new AssertionError("selector did not initialize buttons");
        GuiButton options=find(current.testButtons(),3);
        if(options==null)throw new AssertionError("Shader Options button missing");
        Method actionMethod=current.getClass().getDeclaredMethod("func_146284_a",GuiButton.class);
        actionMethod.setAccessible(true);
        actionMethod.invoke(current,options);
        GuiScreen optionScreen=Minecraft.func_71410_x().testCurrentScreen();
        if(optionScreen==null||!optionScreen.getClass().getName().endsWith("GuiAcousticShaderOptions"))throw new AssertionError("option editor did not open");
        optionScreen.func_73866_w_();
        optionScreen.func_73863_a(30,80,0.0F);
        if(optionScreen.testButtons().isEmpty())throw new AssertionError("option editor did not initialize buttons");
        System.out.println("[PASS] SRG-only 427x240 Music & Sounds layout + selector/options GUI runtime smoke");
    }
    private static GuiButton find(List<GuiButton> buttons,int id){for(GuiButton b:buttons)if(b.field_146127_k==id)return b;return null;}
    private static void assertNoOverlap(GuiButton a,GuiButton b,String label){
        boolean overlap=a.field_146128_h<b.field_146128_h+b.field_146120_f&&a.field_146128_h+a.field_146120_f>b.field_146128_h&&a.field_146129_i<b.field_146129_i+b.field_146121_g&&a.field_146129_i+a.field_146121_g>b.field_146129_i;
        if(overlap)throw new AssertionError(label+" overlap: aY="+a.field_146129_i+" bY="+b.field_146129_i);
    }
}
