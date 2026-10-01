package dev.acoustic.verification;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small Java-8-only verification utility used by the legacy 1.12.2 release harness. */
public final class VerificationTools {
    private static final String MANIFEST_MAGIC = "ACOUSTIC-RFG-BUILDENV-MANIFEST-V1";
    private static final String BUTTON_DESC = "Lnet/minecraft/client/gui/GuiButton;";

    private VerificationTools() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if ("prepare-rfg-source".equals(command)) {
            prepareRfgSource(rest);
        } else if ("manifest-create".equals(command)) {
            requireArgs(command, rest, 2);
            createManifest(Paths.get(rest[0]), Paths.get(rest[1]));
        } else if ("manifest-verify".equals(command)) {
            requireArgs(command, rest, 2);
            verifyManifest(Paths.get(rest[0]), Paths.get(rest[1]));
        } else if ("manifest-parse".equals(command)) {
            requireArgs(command, rest, 1);
            Map<String, ManifestEntry> parsed = parseManifest(Paths.get(rest[0]).toAbsolutePath().normalize());
            System.out.println("[PASS] RFG buildenv manifest parsed: " + parsed.size() + " entries");
        } else if ("verify-projectile-linkage".equals(command)) {
            requireArgs(command, rest, 1);
            verifyProjectileLinkage(Paths.get(rest[0]));
        } else if ("verify-rfg-jar".equals(command)) {
            requireArgs(command, rest, 1);
            verifyRfgJar(Paths.get(rest[0]));
        } else {
            throw new IllegalArgumentException("unknown verification command: " + command);
        }
    }

    private static void usage() {
        throw new IllegalArgumentException(
            "usage: VerificationTools <prepare-rfg-source|manifest-create|manifest-verify|manifest-parse|verify-projectile-linkage|verify-rfg-jar> ..."
        );
    }

    private static void requireArgs(String command, String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException(command + " expects " + expected + " argument(s), got " + args.length);
        }
    }

    private static void prepareRfgSource(String[] args) throws IOException {
        Path root = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            if ("--root".equals(args[i]) && i + 1 < args.length) {
                root = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                out = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("unexpected prepare-rfg-source argument: " + args[i]);
            }
        }
        if (root == null || out == null) {
            throw new IllegalArgumentException("prepare-rfg-source requires --root <dir> --out <dir>");
        }

        List<Path> roots = Arrays.asList(
            root.resolve("acoustic-api/src/main/kotlin"),
            root.resolve("acoustic-platform-api/src/main/kotlin"),
            root.resolve("acoustic-core/src/main/kotlin"),
            root.resolve("minecraft-1.12.2/src/main/kotlin"),
            root.resolve("minecraft-1.12.2/src/forge/kotlin")
        );
        for (Path sourceRoot : roots) {
            if (!Files.isDirectory(sourceRoot)) {
                throw new IllegalStateException("missing source root: " + sourceRoot);
            }
        }

        deleteRecursively(out);
        Files.createDirectories(out);
        int copied = 0;
        for (Path sourceRoot : roots) {
            List<Path> sources = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .forEach(sources::add);
            }
            Collections.sort(sources, Comparator.comparing(path -> sourceRoot.relativize(path).toString()));
            for (Path source : sources) {
                Path relative = sourceRoot.relativize(source);
                Path dest = out.resolve(relative);
                if (Files.exists(dest)) {
                    throw new IllegalStateException("duplicate Kotlin source path across production roots: " + relative);
                }
                Files.createDirectories(dest.getParent());
                Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
        }

        Map<String, GuiRule> rules = guiRules();
        int aliasCount = 0;
        int superCount = 0;
        for (Map.Entry<String, GuiRule> entry : rules.entrySet()) {
            Path path = findExactlyOne(out, entry.getKey());
            String source = readUtf8(path);
            for (String alias : entry.getValue().aliases) {
                Pattern pattern = Pattern.compile(
                    "(?m)^\\s*override\\s+fun\\s+" + Pattern.quote(alias)
                    + "\\([^\\n]*\\)\\s*(?::\\s*[^=\\n]+)?\\s*=\\s*[^\\n]+\\n"
                );
                Matcher matcher = pattern.matcher(source);
                int count = 0;
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    count++;
                    matcher.appendReplacement(sb, "");
                }
                matcher.appendTail(sb);
                if (count != 1) {
                    throw new IllegalStateException(
                        "expected exactly one RFG SRG alias " + alias + " in " + entry.getKey() + ", removed " + count
                    );
                }
                source = sb.toString();
                aliasCount += count;
            }
            for (Map.Entry<String, String> superRule : entry.getValue().superCalls.entrySet()) {
                String oldValue = "super." + superRule.getKey() + "(";
                String newValue = "super." + superRule.getValue() + "(";
                int count = countOccurrences(source, oldValue);
                if (count != 1) {
                    throw new IllegalStateException(
                        "expected exactly one " + oldValue + " in " + entry.getKey() + ", found " + count
                    );
                }
                source = source.replace(oldValue, newValue);
                superCount += count;
            }
            writeUtf8(path, source);
        }

        Path projectile = findExactlyOne(out, "LegacyProjectileEmitterManager.kt");
        String projectileSource = readUtf8(projectile);
        Pattern projectilePattern = Pattern.compile("(?m)^\\s*fun\\s+func_73660_a\\(\\)\\s*=\\s*update\\(\\)\\s*$\\n?");
        Matcher projectileMatcher = projectilePattern.matcher(projectileSource);
        int projectileAliasCount = 0;
        StringBuffer projectileBuffer = new StringBuffer();
        while (projectileMatcher.find()) {
            projectileAliasCount++;
            projectileMatcher.appendReplacement(projectileBuffer, "");
        }
        projectileMatcher.appendTail(projectileBuffer);
        if (projectileAliasCount != 1) {
            throw new IllegalStateException(
                "expected exactly one projectile SRG tick alias, removed " + projectileAliasCount
            );
        }
        projectileSource = projectileBuffer.toString();
        if (!Pattern.compile("\\boverride\\s+fun\\s+update\\(\\)").matcher(projectileSource).find()) {
            throw new IllegalStateException("required MCP projectile update() override missing after RFG normalization");
        }
        writeUtf8(projectile, projectileSource);

        for (Map.Entry<String, GuiRule> entry : rules.entrySet()) {
            Path path = findExactlyOne(out, entry.getKey());
            String source = readUtf8(path);
            for (String alias : entry.getValue().aliases) {
                if (Pattern.compile("\\boverride\\s+fun\\s+" + Pattern.quote(alias) + "\\b").matcher(source).find()) {
                    throw new IllegalStateException("SRG override survived RFG normalization: " + entry.getKey() + ":" + alias);
                }
            }
            for (String mcp : Arrays.asList("initGui", "actionPerformed", "drawScreen")) {
                if (!Pattern.compile("\\boverride\\s+fun\\s+" + Pattern.quote(mcp) + "\\b").matcher(source).find()) {
                    throw new IllegalStateException("required MCP override missing after normalization: " + entry.getKey() + ":" + mcp);
                }
            }
        }

        int expectedAliases = 0;
        int expectedSupers = 0;
        for (GuiRule rule : rules.values()) {
            expectedAliases += rule.aliases.size();
            expectedSupers += rule.superCalls.size();
        }
        if (aliasCount != expectedAliases || superCount != expectedSupers) {
            throw new IllegalStateException(
                "normalization count mismatch aliases=" + aliasCount + "/" + expectedAliases
                + " supers=" + superCount + "/" + expectedSupers
            );
        }
        System.out.println(
            "[PASS] prepared RFG MCP source view: " + copied + " Kotlin files, removed " + aliasCount
            + " explicit SRG GUI aliases + " + projectileAliasCount + " projectile tick alias, rewrote "
            + superCount + " SRG super-calls"
        );
    }

    private static Map<String, GuiRule> guiRules() {
        Map<String, GuiRule> rules = new LinkedHashMap<String, GuiRule>();
        rules.put("GuiAcousticShaders.kt", new GuiRule(
            Arrays.asList("func_73866_w_", "func_146284_a", "func_73863_a", "func_73864_a", "func_146273_a", "func_146286_b"),
            linkedMap(new String[][] {
                {"func_73863_a", "drawScreen"},
                {"func_73864_a", "mouseClicked"},
                {"func_146286_b", "mouseReleased"}
            })
        ));
        rules.put("GuiAcousticShaderOptions.kt", new GuiRule(
            Arrays.asList("func_73866_w_", "func_146284_a", "func_73863_a"),
            linkedMap(new String[][] {{"func_73863_a", "drawScreen"}})
        ));
        rules.put("GuiRuntimeAudio.kt", new GuiRule(
            Arrays.asList("func_73866_w_", "func_146284_a", "func_73863_a"),
            linkedMap(new String[][] {{"func_73863_a", "drawScreen"}})
        ));
        return rules;
    }

    private static Map<String, String> linkedMap(String[][] entries) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String[] entry : entries) {
            result.put(entry[0], entry[1]);
        }
        return result;
    }

    private static Path findExactlyOne(Path root, String fileName) throws IOException {
        List<Path> matches = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals(fileName))
                .forEach(matches::add);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one " + fileName + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static void createManifest(Path rootInput, Path manifestInput) throws Exception {
        Path root = rootInput.toAbsolutePath().normalize();
        Path manifest = manifestInput.toAbsolutePath().normalize();
        List<ManifestEntry> rows = manifestEntries(root);
        Path parent = manifest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(manifest), StandardCharsets.UTF_8))) {
            out.write(MANIFEST_MAGIC);
            out.write('\n');
            for (ManifestEntry row : rows) {
                requireSafeField(row.relative, "manifest path");
                if ("L".equals(row.kind)) {
                    requireSafeField(row.target, "symlink target");
                }
                out.write(row.kind);
                out.write('\t');
                out.write(Long.toString(row.size));
                out.write('\t');
                out.write(row.digest);
                out.write('\t');
                out.write(row.relative);
                if ("L".equals(row.kind)) {
                    out.write('\t');
                    out.write(row.target);
                }
                out.write('\n');
            }
        }
        System.out.println("[PASS] RFG buildenv payload manifest created: " + rows.size() + " entries");
    }

    private static void verifyManifest(Path rootInput, Path manifestInput) throws Exception {
        Path root = rootInput.toAbsolutePath().normalize();
        Map<String, ManifestEntry> expected = parseManifest(manifestInput.toAbsolutePath().normalize());
        Map<String, ManifestEntry> actual = new HashMap<String, ManifestEntry>();
        for (ManifestEntry entry : manifestEntries(root)) {
            actual.put(entry.relative, entry);
        }
        List<String> missing = difference(expected.keySet(), actual.keySet());
        List<String> extra = difference(actual.keySet(), expected.keySet());
        List<String> mismatched = new ArrayList<String>();
        for (String path : expected.keySet()) {
            if (actual.containsKey(path) && !expected.get(path).samePayload(actual.get(path))) {
                mismatched.add(path);
            }
        }
        Collections.sort(mismatched);
        if (!missing.isEmpty() || !extra.isEmpty() || !mismatched.isEmpty()) {
            if (!missing.isEmpty()) {
                System.err.println("ERROR: manifest missing payload entries:");
                printFirst25(missing);
            }
            if (!extra.isEmpty()) {
                System.err.println("ERROR: manifest has unexpected payload entries:");
                printFirst25(extra);
            }
            if (!mismatched.isEmpty()) {
                System.err.println("ERROR: manifest payload mismatch:");
                int limit = Math.min(25, mismatched.size());
                for (int i = 0; i < limit; i++) {
                    String rel = mismatched.get(i);
                    System.err.println("  " + rel);
                    System.err.println("    expected=" + expected.get(rel));
                    System.err.println("    actual=" + actual.get(rel));
                }
            }
            throw new IllegalStateException("RFG buildenv manifest verification failed");
        }
        System.out.println("[PASS] RFG buildenv payload manifest verified: " + expected.size() + " entries");
    }

    private static List<ManifestEntry> manifestEntries(final Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("manifest root is not a directory: " + root);
        }
        final List<ManifestEntry> result = new ArrayList<ManifestEntry>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    if (Files.isSymbolicLink(file)) {
                        String target = Files.readSymbolicLink(file).toString();
                        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
                        result.add(new ManifestEntry("L", targetBytes.length, sha256(targetBytes), relative, target));
                    } else if (attrs.isRegularFile()) {
                        result.add(new ManifestEntry("F", attrs.size(), sha256File(file), relative, ""));
                    } else {
                        throw new IllegalStateException("unsupported payload entry: " + file);
                    }
                    return FileVisitResult.CONTINUE;
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
            }
        });
        Collections.sort(result, Comparator.comparing(entry -> entry.relative));
        return result;
    }

    private static Map<String, ManifestEntry> parseManifest(Path manifest) throws Exception {
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !MANIFEST_MAGIC.equals(lines.get(0))) {
            throw new IllegalStateException("invalid RFG buildenv manifest header");
        }
        Map<String, ManifestEntry> expected = new LinkedHashMap<String, ManifestEntry>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            if (parts.length != 4 && parts.length != 5) {
                throw new IllegalStateException("invalid manifest row: " + line);
            }
            String kind = parts[0];
            if (!"F".equals(kind) && !"L".equals(kind)) {
                throw new IllegalStateException("invalid manifest kind: " + kind);
            }
            long size = Long.parseLong(parts[1]);
            String digest = parts[2];
            String relative = parts[3];
            String target = parts.length == 5 ? parts[4] : "";
            if (relative.startsWith("/") || "..".equals(relative) || relative.startsWith("../") || relative.contains("/../")) {
                throw new IllegalStateException("unsafe manifest path: " + relative);
            }
            if (expected.containsKey(relative)) {
                throw new IllegalStateException("duplicate manifest path: " + relative);
            }
            expected.put(relative, new ManifestEntry(kind, size, digest, relative, target));
        }
        return expected;
    }

    private static void verifyProjectileLinkage(Path javapFile) throws IOException {
        String text = readUtf8(javapFile);
        List<String> forbidden = Arrays.asList(
            "repeat", "repeatDelay", "attenuationType", "donePlaying",
            "xPosF", "yPosF", "zPosF", "volume", "pitch"
        );
        List<String> hits = new ArrayList<String>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.contains("Fieldref") && !line.contains("// Field ")) {
                continue;
            }
            for (String name : forbidden) {
                if (Pattern.compile("\\." + Pattern.quote(name) + ":").matcher(line).find()) {
                    hits.add(line.trim());
                }
            }
        }
        if (!hits.isEmpty()) {
            throw new IllegalStateException(
                "projectile class contains MCP-only inherited Fieldref(s):\n" + String.join("\n", hits)
            );
        }
        List<String> required = Arrays.asList(
            "field_147659_g", "field_147665_h", "field_147666_i", "field_147668_j",
            "field_147660_d", "field_147661_e", "field_147658_f", "field_147662_b", "field_147663_c"
        );
        List<String> missing = new ArrayList<String>();
        for (String name : required) {
            if (!text.contains(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("projectile class is missing SRG reflection literal(s): " + String.join(", ", missing));
        }
        if (!text.contains("ForgeReflection.setField") && !text.contains("ForgeReflection.setField:")) {
            throw new IllegalStateException("projectile class does not link ForgeReflection.setField");
        }
        System.out.println("[PASS] projectile inherited MovingSound fields are reflection-bridged; no MCP-only Fieldref leakage");
    }

    private static void verifyRfgJar(Path jarPath) throws Exception {
        Path jar = jarPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("RFG JAR missing: " + jar);
        }
        int checked = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (GuiContract contract : guiContracts()) {
                JarEntry entry = jf.getJarEntry(contract.entry);
                if (entry == null) {
                    throw new IllegalStateException("RFG JAR missing GUI class " + contract.entry);
                }
                Set<MethodSig> methods;
                try (InputStream input = jf.getInputStream(entry)) {
                    methods = methodTable(readAllBytes(input));
                }
                for (MethodSig sig : contract.requiredSrg) {
                    if (!methods.contains(sig)) {
                        throw new IllegalStateException(
                            "RFG reobf did not produce SRG override " + contract.entry + ":" + sig.name + sig.desc
                        );
                    }
                    checked++;
                }
                for (MethodSig sig : contract.forbiddenMcp) {
                    if (methods.contains(sig)) {
                        throw new IllegalStateException(
                            "MCP GUI override survived RFG reobf " + contract.entry + ":" + sig.name + sig.desc
                        );
                    }
                }
            }
        }
        System.out.println("[PASS] RFG inheritance reobf produced " + checked + " SRG GUI overrides with no MCP duplicates");
    }

    private static List<GuiContract> guiContracts() {
        List<GuiContract> contracts = new ArrayList<GuiContract>();
        contracts.add(new GuiContract(
            "dev/acoustic/mc1122/forge/GuiAcousticShaders.class",
            sigs(new String[][] {
                {"func_73866_w_", "()V"},
                {"func_146284_a", "(" + BUTTON_DESC + ")V"},
                {"func_73863_a", "(IIF)V"},
                {"func_73864_a", "(III)V"},
                {"func_146273_a", "(IIIJ)V"},
                {"func_146286_b", "(III)V"}
            }),
            sigs(new String[][] {
                {"initGui", "()V"},
                {"actionPerformed", "(" + BUTTON_DESC + ")V"},
                {"drawScreen", "(IIF)V"},
                {"mouseClicked", "(III)V"},
                {"mouseClickMove", "(IIIJ)V"},
                {"mouseReleased", "(III)V"}
            })
        ));
        contracts.add(new GuiContract(
            "dev/acoustic/mc1122/forge/GuiAcousticShaderOptions.class",
            sigs(new String[][] {
                {"func_73866_w_", "()V"},
                {"func_146284_a", "(" + BUTTON_DESC + ")V"},
                {"func_73863_a", "(IIF)V"}
            }),
            sigs(new String[][] {
                {"initGui", "()V"},
                {"actionPerformed", "(" + BUTTON_DESC + ")V"},
                {"drawScreen", "(IIF)V"}
            })
        ));
        contracts.add(new GuiContract(
            "dev/acoustic/mc1122/forge/GuiRuntimeAudio.class",
            sigs(new String[][] {
                {"func_73866_w_", "()V"},
                {"func_146284_a", "(" + BUTTON_DESC + ")V"},
                {"func_73863_a", "(IIF)V"}
            }),
            sigs(new String[][] {
                {"initGui", "()V"},
                {"actionPerformed", "(" + BUTTON_DESC + ")V"},
                {"drawScreen", "(IIF)V"}
            })
        ));
        return contracts;
    }

    private static Set<MethodSig> methodTable(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IllegalStateException("bad class magic");
        }
        in.readUnsignedShort();
        int major = in.readUnsignedShort();
        if (major != 52) {
            throw new IllegalStateException("expected Java 8 classfile, got major=" + major);
        }
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8[i] = in.readUTF();
                    break;
                case 3:
                case 4:
                    skipFully(in, 4);
                    break;
                case 5:
                case 6:
                    skipFully(in, 8);
                    i++;
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    skipFully(in, 2);
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    skipFully(in, 4);
                    break;
                case 15:
                    skipFully(in, 3);
                    break;
                default:
                    throw new IllegalStateException("unsupported constant-pool tag " + tag + " at " + i);
            }
        }
        skipFully(in, 6);
        int interfaces = in.readUnsignedShort();
        skipFully(in, interfaces * 2L);
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            skipFully(in, 6);
            skipAttributes(in, in.readUnsignedShort());
        }
        Set<MethodSig> methods = new HashSet<MethodSig>();
        int methodCount = in.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            in.readUnsignedShort();
            int nameIndex = in.readUnsignedShort();
            int descIndex = in.readUnsignedShort();
            String name = utf8[nameIndex];
            String desc = utf8[descIndex];
            if (name == null || desc == null) {
                throw new IllegalStateException("method name/descriptor missing from UTF8 pool");
            }
            methods.add(new MethodSig(name, desc));
            skipAttributes(in, in.readUnsignedShort());
        }
        return methods;
    }

    private static void skipAttributes(DataInputStream in, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            long length = ((long) in.readInt()) & 0xffffffffL;
            skipFully(in, length);
        }
    }

    private static void skipFully(DataInputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            int skipped = in.skipBytes((int) Math.min(Integer.MAX_VALUE, remaining));
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("truncated classfile");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static Set<MethodSig> sigs(String[][] values) {
        Set<MethodSig> result = new HashSet<MethodSig>();
        for (String[] value : values) {
            result.add(new MethodSig(value[0], value[1]));
        }
        return result;
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void writeUtf8(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int start = 0;
        while (true) {
            int index = text.indexOf(needle, start);
            if (index < 0) {
                return count;
            }
            count++;
            start = index + needle.length();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static void requireSafeField(String value, String label) {
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalStateException("unsafe " + label + ": " + value);
        }
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        List<String> result = new ArrayList<String>();
        for (String item : left) {
            if (!right.contains(item)) {
                result.add(item);
            }
        }
        Collections.sort(result);
        return result;
    }

    private static void printFirst25(List<String> values) {
        int limit = Math.min(25, values.size());
        for (int i = 0; i < limit; i++) {
            System.err.println("  " + values.get(i));
        }
    }

    private static final class GuiRule {
        final List<String> aliases;
        final Map<String, String> superCalls;

        GuiRule(List<String> aliases, Map<String, String> superCalls) {
            this.aliases = aliases;
            this.superCalls = superCalls;
        }
    }

    private static final class ManifestEntry {
        final String kind;
        final long size;
        final String digest;
        final String relative;
        final String target;

        ManifestEntry(String kind, long size, String digest, String relative, String target) {
            this.kind = kind;
            this.size = size;
            this.digest = digest;
            this.relative = relative;
            this.target = target;
        }

        boolean samePayload(ManifestEntry other) {
            return kind.equals(other.kind) && size == other.size && digest.equals(other.digest) && target.equals(other.target);
        }

        @Override
        public String toString() {
            return "(" + kind + "," + size + "," + digest + "," + target + ")";
        }
    }

    private static final class MethodSig {
        final String name;
        final String desc;

        MethodSig(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof MethodSig)) {
                return false;
            }
            MethodSig other = (MethodSig) value;
            return name.equals(other.name) && desc.equals(other.desc);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + desc.hashCode();
        }
    }

    private static final class GuiContract {
        final String entry;
        final Set<MethodSig> requiredSrg;
        final Set<MethodSig> forbiddenMcp;

        GuiContract(String entry, Set<MethodSig> requiredSrg, Set<MethodSig> forbiddenMcp) {
            this.entry = entry;
            this.requiredSrg = requiredSrg;
            this.forbiddenMcp = forbiddenMcp;
        }
    }
}
