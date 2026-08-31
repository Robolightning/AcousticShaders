package net.minecraftforge.fml.common.eventhandler;
public class Event {
    private boolean canceled;
    public Event() {}
    public boolean isCancelable(){return true;}
    public boolean isCanceled(){return canceled;}
    public void setCanceled(boolean value){canceled=value;}
}
