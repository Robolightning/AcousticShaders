package net.minecraftforge.client.event;
import java.util.List;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.Event;
public class GuiScreenEvent extends Event {
    private final GuiScreen gui;
    public GuiScreenEvent(GuiScreen gui){this.gui=gui;}
    public GuiScreen getGui(){return gui;}
    public static class InitGuiEvent extends GuiScreenEvent {
        private List<GuiButton> buttons;
        public InitGuiEvent(GuiScreen gui,List<GuiButton> buttons){super(gui);this.buttons=buttons;}
        public List<GuiButton> getButtonList(){return buttons;}
        public void setButtonList(List<GuiButton> buttons){this.buttons=buttons;}
        public static class Post extends InitGuiEvent {public Post(GuiScreen gui,List<GuiButton> buttons){super(gui,buttons);}}
    }
    public static class ActionPerformedEvent extends GuiScreenEvent {
        private GuiButton button;
        private List<GuiButton> buttonList;
        public ActionPerformedEvent(GuiScreen gui,GuiButton button,List<GuiButton> buttonList){super(gui);this.button=button;this.buttonList=buttonList;}
        public GuiButton getButton(){return button;}
        public void setButton(GuiButton button){this.button=button;}
        public List<GuiButton> getButtonList(){return buttonList;}
        public void setButtonList(List<GuiButton> buttonList){this.buttonList=buttonList;}
        public static class Pre extends ActionPerformedEvent {public Pre(GuiScreen gui,GuiButton button,List<GuiButton> buttonList){super(gui,button,buttonList);}}
    }
}
