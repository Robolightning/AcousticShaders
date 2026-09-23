package net.minecraftforge.fluids;
public final class Fluid {
 private final String name; private final int density,temperature; private final boolean gaseous;
 public Fluid(String name,int density,int temperature,boolean gaseous){this.name=name;this.density=density;this.temperature=temperature;this.gaseous=gaseous;}
 public String getName(){return name;} public int getDensity(){return density;} public int getTemperature(){return temperature;} public boolean isGaseous(){return gaseous;}
}
