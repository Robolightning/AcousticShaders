package net.minecraftforge.fml.common.gameevent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.relauncher.Side;
public class TickEvent extends Event {
    public enum Type { CLIENT, SERVER, WORLD, PLAYER, RENDER }
    public enum Phase { START, END }
    public final Type type;
    public final Side side;
    public final Phase phase;
    public TickEvent(Type type, Side side, Phase phase){this.type=type;this.side=side;this.phase=phase;}
    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase){super(Type.CLIENT, Side.CLIENT, phase);}
    }
}
