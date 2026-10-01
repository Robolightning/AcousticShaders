import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Audits the bytecode that is actually packaged by Acoustic Shaders against the official
 * remapped Minecraft/Forge binaries. The source-level real-SRG compile proves that the sources
 * can compile against SRG. This second gate proves that the deterministic release classes do not
 * contain a stale MCP-only Methodref/Fieldref that happened to compile only because of a stub.
 */
public final class RealSrgBytecodeAudit {
    private static final int ASM_API = Opcodes.ASM8;

    private static final class ClassInfo {
        final String name;
        final String superName;
        final List<String> interfaces;
        final Set<String> methods = new HashSet<String>();
        final Set<String> fields = new HashSet<String>();

        ClassInfo(String name, String superName, String[] interfaces) {
            this.name = name;
            this.superName = superName;
            List<String> copy = new ArrayList<String>();
            if (interfaces != null) Collections.addAll(copy, interfaces);
            this.interfaces = Collections.unmodifiableList(copy);
        }
    }

    private static final class Repository {
        final Map<String, ClassInfo> classes = new HashMap<String, ClassInfo>();

        void addJar(Path jar) throws IOException {
            try (JarFile jf = new JarFile(jar.toFile())) {
                for (JarEntry e : Collections.list(jf.entries())) {
                    if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                    try (InputStream in = jf.getInputStream(e)) {
                        addClass(in.readAllBytes());
                    }
                }
            }
        }

        void addClass(byte[] bytes) {
            ClassReader cr = new ClassReader(bytes);
            cr.accept(new ClassVisitor(ASM_API) {
                private ClassInfo info;

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    info = new ClassInfo(name, superName, interfaces);
                    classes.put(name, info);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    info.fields.add(name + descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    info.methods.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        boolean hasMethod(String owner, String name, String desc) {
            return hasMethod(owner, name, desc, new HashSet<String>());
        }

        private boolean hasMethod(String owner, String name, String desc, Set<String> seen) {
            if (owner == null || !seen.add(owner)) return false;
            ClassInfo c = classes.get(owner);
            if (c == null) return false;
            if (c.methods.contains(name + desc)) return true;
            if ("<init>".equals(name)) return false;
            if (hasMethod(c.superName, name, desc, seen)) return true;
            for (String itf : c.interfaces) if (hasMethod(itf, name, desc, seen)) return true;
            return false;
        }

        boolean hasField(String owner, String name, String desc) {
            return hasField(owner, name, desc, new HashSet<String>());
        }

        private boolean hasField(String owner, String name, String desc, Set<String> seen) {
            if (owner == null || !seen.add(owner)) return false;
            ClassInfo c = classes.get(owner);
            if (c == null) return false;
            if (c.fields.contains(name + desc)) return true;
            if (hasField(c.superName, name, desc, seen)) return true;
            for (String itf : c.interfaces) if (hasField(itf, name, desc, seen)) return true;
            return false;
        }
    }

    private static boolean auditedOwner(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("net/minecraftforge/");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: RealSrgBytecodeAudit <classes-dir> <minecraft-srg.jar> <forge-srg.jar>");
            System.exit(2);
        }
        Path classesDir = Paths.get(args[0]);
        Repository repo = new Repository();
        repo.addJar(Paths.get(args[1]));
        repo.addJar(Paths.get(args[2]));

        List<String> errors = new ArrayList<String>();
        int[] classCount = {0};
        int[] memberRefs = {0};

        try (java.util.stream.Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(p -> p.toString().endsWith(".class")).sorted().forEach(path -> {
                classCount[0]++;
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    ClassReader cr = new ClassReader(bytes);
                    final String[] currentClass = {"?"};
                    cr.accept(new ClassVisitor(ASM_API) {
                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superName, String[] interfaces) {
                            currentClass[0] = name;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String methodName, String methodDesc,
                                                         String signature, String[] exceptions) {
                            return new MethodVisitor(ASM_API) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                    if (!auditedOwner(owner)) return;
                                    memberRefs[0]++;
                                    if (!repo.hasField(owner, name, descriptor)) {
                                        errors.add(currentClass[0] + "." + methodName + methodDesc
                                            + " -> missing field " + owner + "." + name + " " + descriptor);
                                    }
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name,
                                                            String descriptor, boolean isInterface) {
                                    if (!auditedOwner(owner)) return;
                                    memberRefs[0]++;
                                    if (!repo.hasMethod(owner, name, descriptor)) {
                                        errors.add(currentClass[0] + "." + methodName + methodDesc
                                            + " -> missing method " + owner + "." + name + descriptor);
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        if (!errors.isEmpty()) {
            Collections.sort(errors);
            System.err.println("ERROR: deterministic production bytecode contains " + errors.size()
                + " Minecraft/Forge member reference(s) absent from the official SRG binaries:");
            for (String e : errors) System.err.println("  " + e);
            System.exit(1);
        }
        System.out.println("[PASS] packaged-production bytecode resolves " + memberRefs[0]
            + " Minecraft/Forge member refs against official SRG binaries across " + classCount[0] + " classes");
    }
}
