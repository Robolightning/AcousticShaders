package net.minecraft.client.gui;
public class GuiScreenOptionsSounds extends GuiScreen {
    public GuiScreenOptionsSounds(){
        /* Representative bottom of vanilla 1.12.2 category sliders at 427x240. */
        field_146292_n.add(new GuiButton(7,123,148,150,20,"Voice/Speech: 100%"));
        field_146292_n.add(new GuiButton(201,138,172,150,20,"Show Subtitles: OFF"));
        field_146292_n.add(new GuiButton(200,113,208,200,20,"Done"));
    }
}
