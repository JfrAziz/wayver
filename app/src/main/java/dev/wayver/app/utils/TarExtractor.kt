package dev.wayver.app.utils

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Extracts `.tar` and `.tar.gz` rootfs archives into a destination directory.
 *
 * Uses `android.system.Os` for symlinks, hardlinks, and POSIX permissions because
 * `java.io.File` cannot represent symlinks or arbitrary Unix mode bits.
 */
object TarExtractor {
    private const val IO_BUFFER_SIZE = 65536
    private const val TAR_BLOCK_SIZE = 512

    private data class TarHeader(
        val rawPath: String,
        val mode: Long,
        val size: Long,
        val typeFlag: Char,
        val linkTarget: String
    )

    fun extract(rawStream: InputStream, destRoot: File) {
        openTarStream(rawStream).use { stream ->
            extractTar(stream, destRoot)
        }
    }

    /**
     * Sniffs the gzip magic header (0x1f 0x8b) instead of trusting the file extension,
     * since user-imported archives may be plain `.tar` or `.tar.gz`.
     */
    private fun openTarStream(rawStream: InputStream): InputStream {
        val buffered = BufferedInputStream(rawStream, IO_BUFFER_SIZE)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, IO_BUFFER_SIZE) else buffered
    }

    private fun extractTar(input: InputStream, destRoot: File) {
        val headerBuf = ByteArray(TAR_BLOCK_SIZE)
        var longName: String? = null
        var longLink: String? = null
        val dirModes = ArrayList<Pair<File, Int>>()

        while (true) {
            val read = readFully(input, headerBuf)
            if (read < TAR_BLOCK_SIZE || headerBuf.all { it == 0.toByte() }) break

            val header = parseTarHeader(headerBuf, longName, longLink)
            longName = null
            longLink = null

            // GNU tar stores paths/links >100 bytes in a preceding 'L' or 'K' metadata record.
            if (header.typeFlag == 'L') {
                longName = readTarMetadataString(input, header.size)
                continue
            }
            if (header.typeFlag == 'K') {
                longLink = readTarMetadataString(input, header.size)
                continue
            }
            // Skip PAX per-file ('x') and global ('g') extended headers.
            if (header.typeFlag == 'x' || header.typeFlag == 'g') {
                skipBytes(input, header.size)
                skipPadding(input, header.size)
                continue
            }

            val cleanPath = header.rawPath.trimStart('/', '.').trimStart('/')
            if (cleanPath.isEmpty()) {
                skipBytes(input, header.size)
                skipPadding(input, header.size)
                continue
            }

            extractTarEntry(input, File(destRoot, cleanPath), destRoot, header, dirModes)
        }

        // Apply directory permissions bottom-up after all files are extracted; setting a restrictive
        // mode like 0555/0500 during top-down extraction would block creating child entries inside it.
        for (i in dirModes.indices.reversed()) {
            val (dir, dirMode) = dirModes[i]
            runCatching { Os.chmod(dir.absolutePath, dirMode) }
        }
    }

    private fun parseTarHeader(buf: ByteArray, longName: String?, longLink: String?): TarHeader {
        val nameRaw = readString(buf, 0, 100)
        val prefix = readString(buf, 345, 155)
        val rawPath = longName ?: if (prefix.isNotEmpty()) "$prefix/$nameRaw" else nameRaw
        return TarHeader(
            rawPath = rawPath,
            mode = readOctal(buf, 100, 8),
            size = readOctal(buf, 124, 12),
            typeFlag = buf[156].toInt().toChar(),
            linkTarget = longLink ?: readString(buf, 157, 100)
        )
    }

    private fun readTarMetadataString(input: InputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        readFully(input, buf)
        skipPadding(input, size)
        return String(buf, StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun extractTarEntry(
        input: InputStream,
        outFile: File,
        destRoot: File,
        header: TarHeader,
        dirModes: MutableList<Pair<File, Int>>
    ) {
        when (header.typeFlag) {
            '5' -> {
                outFile.mkdirs()
                val dirMode = header.mode.toInt() and 0xFFF
                if (dirMode != 0) dirModes.add(outFile to dirMode)
            }

            '2' -> {
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(header.linkTarget, outFile.absolutePath) }
            }

            '1' -> {
                outFile.parentFile?.mkdirs()
                val targetFile = File(destRoot, header.linkTarget.trimStart('/', '.').trimStart('/'))
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(targetFile.absolutePath, outFile.absolutePath) }
            }

            // Skip device nodes and FIFOs: unprivileged mknod fails on Android, and PRoot binds /dev.
            '3', '4', '6' -> Unit

            else -> {
                outFile.parentFile?.mkdirs()
                copyFixedBytes(input, outFile, header.size)
                runCatching { Os.chmod(outFile.absolutePath, header.mode.toInt() and 0x1FF) }
            }
        }
        if (header.typeFlag != '0' && header.typeFlag != '\u0000') {
            skipBytes(input, header.size)
        }
        skipPadding(input, header.size)
    }

    private fun copyFixedBytes(input: InputStream, outFile: File, count: Long) {
        outFile.outputStream().use { fos ->
            val buf = ByteArray(16384)
            var rem = count
            while (rem > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                if (n <= 0) break
                fos.write(buf, 0, n)
                rem -= n
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var rem = count
        val buf = ByteArray(4096)
        while (rem > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n <= 0) break
            rem -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val rem = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
        if (rem > 0) skipBytes(input, rem)
    }

    private fun readString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, StandardCharsets.UTF_8)
    }

    private fun readOctal(buf: ByteArray, off: Int, len: Int): Long {
        val s = readString(buf, off, len).trim()
        return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
    }
}
