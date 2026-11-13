package dev.wayver.app.utils

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a remote file atomically via a `.part` staging file so interrupted transfers
 * never leave a partial/corrupt artifact at [target].
 */
object HttpDownloader {
    private const val IO_BUFFER_SIZE = 65536

    fun download(urlStr: String, target: File, onProgress: (String) -> Unit): Boolean {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        return try {
            onProgress("Connecting...")
            val conn =
                (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }
            val code = conn.responseCode
            if (code !in 200..299) {
                onProgress("HTTP $code")
                return false
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L
            var lastMb = -1L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        val mb = downloaded / (1024 * 1024)
                        if (mb != lastMb) {
                            lastMb = mb
                            val totalStr = if (totalBytes > 0) " / ${totalBytes / (1024 * 1024)} MB" else ""
                            onProgress("Downloading $mb MB$totalStr...")
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            part.renameTo(target)
        } finally {
            part.delete()
        }
    }
}
