#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

JAR="${1:-}"
[[ -f "$JAR" ]] || { echo 'ERROR: usage: kotlin-runtime-package-contract.sh <built-jar>' >&2; exit 2; }
for cmd in javac java unzip; do command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 2; }; done

WORK="$ROOT/out/1.13.2-kotlin-runtime-package"
rm -rf "$WORK"
mkdir -p "$WORK"
unzip -tqq "$JAR"

cat > "$WORK/KotlinRuntimeProbe.java" <<'JAVA'
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class KotlinRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("jar path required");
        int classes = 0;
        int maxMajor = 0;
        boolean kotlinVersion = false;
        boolean jdk7 = false;
        boolean jdk8 = false;
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals("kotlin/KotlinVersion.class")) kotlinVersion = true;
                if (name.equals("kotlin/internal/jdk7/JDK7PlatformImplementations.class")) jdk7 = true;
                if (name.equals("kotlin/internal/jdk8/JDK8PlatformImplementations.class")) jdk8 = true;
                if (name.startsWith("kotlin/reflect/full/") || name.startsWith("kotlin/reflect/jvm/internal/") || name.startsWith("kotlinx/coroutines/")) {
                    throw new IllegalStateException("unexpected reflect/coroutines payload: " + name);
                }
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if (name.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA"))) {
                    throw new IllegalStateException("signature file leaked into packaged runtime: " + name);
                }
                if (!name.endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] h = new byte[8];
                    int off = 0;
                    while (off < h.length) {
                        int n = in.read(h, off, h.length - off);
                        if (n < 0) throw new IllegalStateException("short class header: " + name);
                        off += n;
                    }
                    if ((h[0] & 0xff) != 0xca || (h[1] & 0xff) != 0xfe || (h[2] & 0xff) != 0xba || (h[3] & 0xff) != 0xbe) {
                        throw new IllegalStateException("bad class magic: " + name);
                    }
                    int major = ((h[6] & 0xff) << 8) | (h[7] & 0xff);
                    if (major > 52) throw new IllegalStateException("non-Java-8 classfile major=" + major + ": " + name);
                    if (major > maxMajor) maxMajor = major;
                    classes++;
                }
            }
        }
        if (!kotlinVersion || !jdk7 || !jdk8) throw new IllegalStateException("required Kotlin runtime classes missing");
        if (!"1.3.50".equals(kotlin.KotlinVersion.CURRENT.toString())) {
            throw new IllegalStateException("Kotlin runtime version mismatch: " + kotlin.KotlinVersion.CURRENT);
        }
        System.out.println("KOTLIN_VERSION=" + kotlin.KotlinVersion.CURRENT);
        System.out.println("JDK7=" + new kotlin.internal.jdk7.JDK7PlatformImplementations().getClass().getName());
        System.out.println("JDK8=" + new kotlin.internal.jdk8.JDK8PlatformImplementations().getClass().getName());
        System.out.println("CLASSES=" + classes);
        System.out.println("MAX_MAJOR=" + maxMajor);
    }
}
JAVA
javac --release 8 -Xlint:all,-options -Werror -cp "$JAR" -d "$WORK" "$WORK/KotlinRuntimeProbe.java"
probe="$(java -cp "$JAR:$WORK" KotlinRuntimeProbe "$JAR")"
grep -Fx 'KOTLIN_VERSION=1.3.50' <<<"$probe" >/dev/null
grep -Fx 'JDK7=kotlin.internal.jdk7.JDK7PlatformImplementations' <<<"$probe" >/dev/null
grep -Fx 'JDK8=kotlin.internal.jdk8.JDK8PlatformImplementations' <<<"$probe" >/dev/null
classes="$(awk -F= '/^CLASSES=/{print $2}' <<<"$probe")"
max_major="$(awk -F= '/^MAX_MAJOR=/{print $2}' <<<"$probe")"
[[ "$classes" =~ ^[0-9]+$ && "$max_major" =~ ^[0-9]+$ ]]
printf '[PASS] 1.13.2 packaged Kotlin runtime: exact 1.3.50 stdlib/jdk7/jdk8, no reflect/coroutines, %d classfiles, max major=%d\n' "$classes" "$max_major"
