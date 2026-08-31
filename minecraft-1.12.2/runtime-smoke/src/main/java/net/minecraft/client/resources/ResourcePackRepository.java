package net.minecraft.client.resources;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public final class ResourcePackRepository{
    public static final class Entry{
        private final String name;
        public Entry(String name){this.name=name;}
        public String getResourcePackName(){return name;}
        public String func_110515_d(){return name;}
        @Override public String toString(){return name;}
    }
    private final List<Entry> all=new ArrayList<Entry>();
    private final List<Entry> selected=new ArrayList<Entry>();
    public int updates;
    public int sets;
    public void addAll(String name){all.add(new Entry(name));}
    public void addSelected(String name){Entry e=new Entry(name);selected.add(e);boolean found=false;for(Entry x:all)if(x.getResourcePackName().equals(name)){found=true;break;}if(!found)all.add(e);}
    public void updateRepositoryEntriesAll(){updates++;}
    public void func_110611_a(){updateRepositoryEntriesAll();}
    public List<Entry> getRepositoryEntriesAll(){return Collections.unmodifiableList(all);}
    public List<Entry> func_110609_b(){return getRepositoryEntriesAll();}
    public List<Entry> getRepositoryEntries(){return Collections.unmodifiableList(selected);}
    public List<Entry> func_110613_c(){return getRepositoryEntries();}
    public void setRepositories(List<Entry> entries){selected.clear();selected.addAll(entries);sets++;}
    public void func_148527_a(List<Entry> entries){setRepositories(entries);}
}
