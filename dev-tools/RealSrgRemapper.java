import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.*;

/** Build-only TSRG v1 remapper used by the offline Minecraft 1.12.2 binary ABI gate. */
public final class RealSrgRemapper {
    private static final class Key {
        final String owner, name, desc;
        Key(String owner, String name, String desc){this.owner=owner;this.name=name;this.desc=desc;}
        public boolean equals(Object o){if(!(o instanceof Key))return false;Key k=(Key)o;return owner.equals(k.owner)&&name.equals(k.name)&&desc.equals(k.desc);}
        public int hashCode(){return Objects.hash(owner,name,desc);}
    }
    private static final class Maps {
        final Map<String,String> classes=new HashMap<>();
        final Map<String,String> fields=new HashMap<>();
        final Map<Key,String> methods=new HashMap<>();
    }
    private static Maps readTsrg(Path path) throws IOException {
        Maps m=new Maps(); String owner=null;
        for(String line:Files.readAllLines(path)){
            if(line.trim().isEmpty()||line.startsWith("#"))continue;
            if(!Character.isWhitespace(line.charAt(0))){
                String[] p=line.trim().split("\\s+");
                if(p.length<2)throw new IOException("bad class mapping: "+line);
                owner=p[0];m.classes.put(p[0],p[1]);
            } else {
                if(owner==null)throw new IOException("member before class: "+line);
                String[] p=line.trim().split("\\s+");
                if(p.length==2)m.fields.put(owner+"\u0000"+p[0],p[1]);
                else if(p.length==3)m.methods.put(new Key(owner,p[0],p[1]),p[2]);
                else throw new IOException("bad member mapping: "+line);
            }
        }
        return m;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3){System.err.println("usage: RealSrgRemapper MAPPINGS.tsrg INPUT.jar OUTPUT.jar");System.exit(2);}
        Maps maps=readTsrg(Paths.get(args[0]));
        Remapper remapper=new Remapper(){
            @Override public String map(String internalName){return maps.classes.getOrDefault(internalName,internalName);}
            @Override public String mapFieldName(String owner,String name,String descriptor){return maps.fields.getOrDefault(owner+"\u0000"+name,name);}
            @Override public String mapMethodName(String owner,String name,String descriptor){return maps.methods.getOrDefault(new Key(owner,name,descriptor),name);}
        };
        Path in=Paths.get(args[1]), out=Paths.get(args[2]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        try(ZipFile z=new ZipFile(in.toFile()); JarOutputStream j=new JarOutputStream(Files.newOutputStream(out))){
            List<? extends ZipEntry> entries=Collections.list(z.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            Set<String> written=new HashSet<>();
            for(ZipEntry e:entries){
                if(e.isDirectory())continue;
                String name=e.getName();
                if(name.equals("META-INF/MANIFEST.MF") || (name.startsWith("META-INF/")&&(name.endsWith(".SF")||name.endsWith(".RSA")||name.endsWith(".DSA"))))continue;
                byte[] data;
                try(InputStream is=z.getInputStream(e)){data=is.readAllBytes();}
                if(name.endsWith(".class")){
                    ClassReader cr=new ClassReader(data); ClassWriter cw=new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw,remapper),0); data=cw.toByteArray();
                    String mapped=remapper.map(cr.getClassName())+".class";
                    name=mapped;
                }
                if(!written.add(name))continue;
                JarEntry je=new JarEntry(name); je.setTime(0L); j.putNextEntry(je); j.write(data); j.closeEntry();
            }
        }
    }
}
