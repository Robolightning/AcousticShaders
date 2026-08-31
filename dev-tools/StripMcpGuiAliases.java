import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/**
 * Build-only helper for the local RFG-reobf-equivalent contract.
 *
 * The deterministic source tree intentionally carries both MCP and SRG GUI entry points.  The real
 * RFG source view removes the explicit SRG aliases before compilation, then inheritance-aware reobf
 * renames the MCP overrides to SRG.  The real-SRG compile already emits the correct SRG methods and
 * external SRG references; this helper removes only the redundant MCP compatibility aliases so the
 * resulting audit JAR has the same GUI method-table invariant as a real RFG reobf JAR.
 */
public final class StripMcpGuiAliases {
    private static final Map<String, Set<String>> DROP = new HashMap<>();
    private static final Map<String, Integer> EXPECTED = new HashMap<>();

    static {
        DROP.put("dev/acoustic/mc1122/forge/GuiAcousticShaders", new HashSet<>(Arrays.asList(
                "initGui()V",
                "actionPerformed(Lnet/minecraft/client/gui/GuiButton;)V",
                "drawScreen(IIF)V",
                "mouseClicked(III)V",
                "mouseClickMove(IIIJ)V",
                "mouseReleased(III)V")));
        EXPECTED.put("dev/acoustic/mc1122/forge/GuiAcousticShaders", 6);

        Set<String> three = new HashSet<>(Arrays.asList(
                "initGui()V",
                "actionPerformed(Lnet/minecraft/client/gui/GuiButton;)V",
                "drawScreen(IIF)V"));
        DROP.put("dev/acoustic/mc1122/forge/GuiAcousticShaderOptions", three);
        DROP.put("dev/acoustic/mc1122/forge/GuiRuntimeAudio", three);
        EXPECTED.put("dev/acoustic/mc1122/forge/GuiAcousticShaderOptions", 3);
        EXPECTED.put("dev/acoustic/mc1122/forge/GuiRuntimeAudio", 3);
    }

    private StripMcpGuiAliases() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: StripMcpGuiAliases CLASSES_DIR");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        int total = 0;
        for (Map.Entry<String, Set<String>> entry : DROP.entrySet()) {
            String internalName = entry.getKey();
            Path classFile = root.resolve(internalName + ".class");
            if (!Files.isRegularFile(classFile)) {
                throw new IOException("missing GUI class: " + classFile);
            }
            byte[] input = Files.readAllBytes(classFile);
            ClassReader reader = new ClassReader(input);
            if (!internalName.equals(reader.getClassName())) {
                throw new IOException("unexpected class name in " + classFile + ": " + reader.getClassName());
            }
            ClassWriter writer = new ClassWriter(0);
            int[] removed = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
                @Override
                public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions) {
                    if (entry.getValue().contains(name + descriptor)) {
                        removed[0]++;
                        return null;
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
            }, 0);
            int expected = EXPECTED.get(internalName);
            if (removed[0] != expected) {
                throw new IOException(
                        "expected to remove " + expected + " MCP GUI aliases from " + internalName
                                + ", removed " + removed[0]);
            }
            Files.write(classFile, writer.toByteArray());
            total += removed[0];
        }
        if (total != 12) {
            throw new IOException("expected 12 total MCP GUI aliases, removed " + total);
        }
        System.out.println("[PASS] stripped exactly 12 deterministic-builder MCP GUI aliases");
    }
}
