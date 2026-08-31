package net.minecraft.client.gui;
public class FontRenderer {
    public int func_78256_a(String s){return s==null?0:s.length()*6;}
    /* Real 1.12.2 signatures: two-arg trim is func_78269_a; func_78262_a has reverse flag. */
    public String func_78269_a(String s,int w){if(s==null)return "";int n=Math.max(0,Math.min(s.length(),w/6));return s.substring(0,n);}
    public String func_78262_a(String s,int w,boolean reverse){return func_78269_a(s,w);}
}
