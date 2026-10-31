package app.cairn.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * One passphrase-encrypted file holding every note and the GitHub index, so a new phone can restore.
 *
 * Format: "CAIRN" 0x01, KDF id (1 = PBKDF2-HMAC-SHA256), iterations (int), salt (16 bytes),
 * IV (12 bytes), then AES-256-GCM ciphertext of a zip. The header is the GCM associated data, so
 * it cannot be altered without the file failing to open. Argon2id would need a native library, so
 * v0.1 uses PBKDF2 with a high iteration count; the KDF id leaves room to add Argon2id later.
 */
object Backup {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte(), 'N'.code.toByte(), 1)
    const val KDF_PBKDF2 = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 8

    /** Files that belong in a backup, relative to the data root. */
    private fun included(rel: String) = rel.startsWith("notes/") || rel == "github.json" || rel == "index.json"

    fun pack(root: File): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (!included(rel)) return@forEach
                zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Replaces notes and the GitHub index under [root] with the archive's contents. */
    fun unpack(data: ByteArray, root: File) {
        val staging = File(root.parentFile, root.name + ".restore").also { it.deleteRecursively(); it.mkdirs() }
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val rel = e.name
                    val target = File(staging, rel).canonicalFile
                    if (!e.isDirectory && included(rel) && target.path.startsWith(staging.canonicalPath + File.separator)) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        e.time.takeIf { it > 0 }?.let { target.setLastModified(it) }
                    }
                    e = zip.nextEntry
                }
            }
            if (!File(staging, "notes").isDirectory) throw BackupException("This backup holds no notes.")
            File(root, "notes").deleteRecursively()
            File(root, "github.json").delete()
            File(root, "index.json").delete()
            staging.walkTopDown().filter { it.isFile }.forEach { f ->
                val dest = File(root, f.relativeTo(staging).path)
                dest.parentFile?.mkdirs()
                f.copyTo(dest, overwrite = true)
                dest.setLastModified(f.lastModified())
            }
            File(root, ".seeded").writeText("1")
        } finally {
            staging.deleteRecursively()
        }
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        if (passphrase.size < MIN_PASSPHRASE) throw BackupException("Use a passphrase of at least $MIN_PASSPHRASE characters.")
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val header = header(iterations, salt, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        val headerSize = MAGIC.size + 1 + 4 + 16 + 12
        if (file.size < headerSize + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This is not a Cairn backup file.")
        }
        val din = DataInputStream(ByteArrayInputStream(file, MAGIC.size, headerSize - MAGIC.size))
        val kdf = din.readUnsignedByte()
        if (kdf != KDF_PBKDF2) throw BackupException("This backup needs a newer version of Cairn.")
        val iterations = din.readInt()
        if (iterations < 1 || iterations > 10_000_000) throw BackupException("This backup file is damaged.")
        val salt = ByteArray(16).also(din::readFully)
        val iv = ByteArray(12).also(din::readFully)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(file, 0, headerSize)
            cipher.doFinal(file, headerSize, file.size - headerSize)
        } catch (_: GeneralSecurityException) {
            throw BackupException("Wrong passphrase, or the file is damaged.")
        }
    }

    private fun header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.write(MAGIC); it.writeByte(KDF_PBKDF2); it.writeInt(iterations); it.write(salt); it.write(iv)
        }
        return bos.toByteArray()
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
