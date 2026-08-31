package net.minecraft.client.settings;
import java.util.ArrayList;
import java.util.List;
public final class GameSettings{
    public final List<String> resourcePacks=new ArrayList<String>();
    public final List<String> field_151453_l=resourcePacks;
    public int saves;
    public void saveOptions(){saves++;}
    public void func_74303_b(){saveOptions();}
}
