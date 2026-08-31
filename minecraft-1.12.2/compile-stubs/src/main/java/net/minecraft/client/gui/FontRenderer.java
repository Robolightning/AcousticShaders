package net.minecraft.client.gui;
public class FontRenderer {
    public int getStringWidth(String s){return s==null?0:s.length()*6;}
    public int func_78256_a(String s){return getStringWidth(s);}
    public String trimStringToWidth(String s,int w){return trimStringToWidth(s,w,false);}
    public String func_78269_a(String s,int w){return trimStringToWidth(s,w,false);}
    public String trimStringToWidth(String s,int w,boolean reverse){if(s==null)return "";int n=Math.max(0,Math.min(s.length(),w/6));return reverse?s.substring(Math.max(0,s.length()-n)):s.substring(0,n);}
    public String func_78262_a(String s,int w,boolean reverse){return trimStringToWidth(s,w,reverse);}
    public int drawStringWithShadow(String s,float x,float y,int color){return 0;}
}
