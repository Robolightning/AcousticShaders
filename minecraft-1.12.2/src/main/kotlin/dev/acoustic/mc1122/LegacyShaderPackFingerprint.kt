package dev.acoustic.mc1122

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayList

/** Content fingerprint for the selected shader-pack stack, used by transactional hot reload. */
object LegacyShaderPackFingerprint {
    @JvmStatic
    @Throws(IOException::class)
    fun compute(shaderpackDir: Path, packs: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        token(digest, "acoustic-shader-stack-v1")
        val root = shaderpackDir.normalize()
        for (name in packs) {
            token(digest, "pack")
            token(digest, name)
            val path = root.resolve(name).normalize()
            if (!path.startsWith(root)) throw IllegalArgumentException("shaderpack escapes shaderpack directory")
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                token(digest, "directory")
                val files = ArrayList<Path>()
                Files.walk(path).use { stream ->
                    stream.forEach { candidate ->
                        if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) files.add(candidate)
                    }
                }
                files.sortBy { path.relativize(it).toString().replace('\\', '/') }
                for (file in files) {
                    token(digest, path.relativize(file).toString().replace('\\', '/'))
                    updateFile(digest, file)
                }
            } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                token(digest, "file")
                updateFile(digest, path)
            } else {
                // Missing/replaced packs must alter the observed fingerprint so a later repair can retrigger reload.
                token(digest, "missing")
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Throws(IOException::class)
    private fun updateFile(digest: MessageDigest, path: Path) {
        token(digest, Files.size(path).toString())
        Files.newInputStream(path).use { input -> copy(input, digest) }
        digest.update(0)
    }

    @Throws(IOException::class)
    private fun copy(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count > 0) digest.update(buffer, 0, count)
        }
    }

    private fun token(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes)
        digest.update(0)
    }
}
