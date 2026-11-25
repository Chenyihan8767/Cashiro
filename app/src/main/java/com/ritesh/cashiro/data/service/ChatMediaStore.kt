package com.ritesh.cashiro.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.ritesh.cashiro.data.model.ChatAttachment
import com.ritesh.cashiro.data.model.ChatAttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatMediaStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val drafts = File(context.cacheDir, "ai-media-drafts")
    private val saved = File(context.filesDir, "ai-media")

    fun file(attachment: ChatAttachment, draft: Boolean = false): File {
        val id = UUID.fromString(attachment.id).toString()
        return File(if (draft) drafts else saved, "$id.${attachment.kind.extension}")
    }

    suspend fun import(uri: Uri, kind: ChatAttachmentKind, temporarySource: Boolean = false): ChatAttachment =
        withContext(Dispatchers.IO) {
            try {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?.take(160) ?: kind.extension.uppercase()
                createDraft(kind, name) { destination ->
                    when (kind) {
                        ChatAttachmentKind.IMAGE -> copyImage(uri, destination)
                        ChatAttachmentKind.AUDIO -> copyWav(uri, destination)
                    }
                }
            } finally {
                if (temporarySource) runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }

    @SuppressLint("MissingPermission")
    suspend fun record(name: String, isRecording: () -> Boolean): ChatAttachment? = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw ChatMediaException(ChatMediaError.RECORDING)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096)
        )
        val samples = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw ChatMediaException(ChatMediaError.RECORDING)
            recorder.startRecording()
            while (samples.size() < MAX_PCM_BYTES && isRecording()) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, minOf(buffer.size, MAX_PCM_BYTES - samples.size()))
                if (count < 0) throw ChatMediaException(ChatMediaError.RECORDING)
                samples.write(buffer, 0, count)
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
        if (samples.size() < SAMPLE_RATE / 4) return@withContext null

        createDraft(ChatAttachmentKind.AUDIO, name) { destination ->
            destination.outputStream().use { output ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(36 + samples.size())
                    put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(SAMPLE_RATE)
                    putInt(SAMPLE_RATE * 2)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(samples.size())
                }.array()
                output.write(header)
                samples.writeTo(output)
            }
        }
    }

    suspend fun saveDrafts(attachments: List<ChatAttachment>) = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext
        saved.mkdirs()
        val written = mutableListOf<File>()
        try {
            attachments.forEach { attachment ->
                val destination = file(attachment)
                written += destination
                file(attachment, draft = true).copyTo(destination)
            }
        } catch (error: Exception) {
            written.forEach { it.delete() }
            throw error
        }
    }

    fun removeDrafts(attachments: List<ChatAttachment>) {
        attachments.forEach { file(it, draft = true).delete() }
    }

    suspend fun removeSaved(attachments: List<ChatAttachment>) = withContext(Dispatchers.IO) {
        attachments.forEach { file(it).delete() }
    }

    private inline fun createDraft(kind: ChatAttachmentKind, name: String, write: (File) -> Unit): ChatAttachment {
        drafts.mkdirs()
        val attachment = ChatAttachment(UUID.randomUUID().toString(), kind, name)
        val destination = file(attachment, draft = true)
        try {
            write(destination)
            return attachment
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun copyImage(uri: Uri, destination: File) {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1.0, MAX_IMAGE_EDGE.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        } else {
            val resolver = context.contentResolver
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid image")
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } ?: throw IOException("Cannot decode image")

        try {
            destination.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("Cannot encode image") }
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyWav(uri: Uri, destination: File) {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            val header = ByteArray(12)
            try {
                java.io.DataInputStream(input).readFully(header)
            } catch (_: IOException) {
                throw ChatMediaException(ChatMediaError.WAV_FORMAT)
            }
            if (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE"
            ) throw ChatMediaException(ChatMediaError.WAV_FORMAT)
            destination.outputStream().buffered().use { output ->
                output.write(header)
                var bytes = header.size
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    bytes += read
                    if (bytes > MAX_WAV_BYTES) throw ChatMediaException(ChatMediaError.AUDIO_TOO_LONG)
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw ChatMediaException(ChatMediaError.WAV_FORMAT)

        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(destination.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw ChatMediaException(ChatMediaError.WAV_FORMAT)
            if (duration > MAX_AUDIO_SECONDS * 1000) throw ChatMediaException(ChatMediaError.AUDIO_TOO_LONG)
        } finally {
            metadata.release()
        }
    }

    companion object {
        const val MAX_AUDIO_SECONDS = 30
        const val MAX_IMAGES = 2
        private const val MAX_IMAGE_EDGE = 1024
        private const val SAMPLE_RATE = 16_000
        private const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_AUDIO_SECONDS
        private const val MAX_WAV_BYTES = 12 * 1024 * 1024
    }
}

enum class ChatMediaError { IMAGE, WAV_FORMAT, AUDIO_TOO_LONG, RECORDING }
class ChatMediaException(val reason: ChatMediaError) : IOException()
