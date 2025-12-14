package com.example.hockeyscoreboard

import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TelegramDocumentSender {

    /**
     * Успех: HTTP 200..299 И Telegram JSON { "ok": true, ... }.
     * Если Telegram вернул ok=false (например, chat_id неверный) — считаем ошибкой даже при HTTP 200.
     */
    fun sendDocument(
        token: String,
        chatId: String,
        file: File,
        contentType: String = "application/json"
    ): Result<Unit> {
        return runCatching {
            require(token.isNotBlank()) { "Telegram token is blank" }
            require(chatId.isNotBlank()) { "Telegram chat_id is blank" }
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }

            val url = URL("https://api.telegram.org/bot$token/sendDocument")
            val boundary = "HSB-${System.currentTimeMillis()}-${file.name}-${chatId}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { output ->
                // chat_id
                output.writeBytes(twoHyphens + boundary + lineEnd)
                output.writeBytes("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd")
                output.writeBytes(chatId + lineEnd)

                // document
                output.writeBytes(twoHyphens + boundary + lineEnd)
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"$lineEnd"
                )
                output.writeBytes("Content-Type: $contentType$lineEnd$lineEnd")

                file.inputStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
                output.writeBytes(lineEnd)

                // close multipart
                output.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                output.flush()
            }

            val code = connection.responseCode

            val body = try {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } catch (_: Exception) {
                ""
            }

            val ok = (code in 200..299) && body.contains("\"ok\":true")

            if (!ok) {
                throw RuntimeException(
                    "Telegram sendDocument failed: HTTP $code, body=$body, file=${file.name}, chat=$chatId"
                )
            }
        }
    }
}
