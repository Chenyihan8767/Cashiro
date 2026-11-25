package com.ritesh.cashiro.presentation.ui.features.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.ritesh.cashiro.R
import com.ritesh.cashiro.data.model.ChatAttachment
import com.ritesh.cashiro.data.model.ChatAttachmentKind
import com.ritesh.cashiro.data.service.ChatMediaError
import com.ritesh.cashiro.data.service.ChatMediaStore
import java.io.File

@Composable
internal fun ChatMediaButton(
    viewModel: ChatViewModel,
    state: ChatMediaState,
    enabled: Boolean,
    onError: (Int) -> Unit
) {
    val context = LocalContext.current
    val recordingName = stringResource(R.string.ai_audio_recording_name)
    var expanded by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.importMedia(it, ChatAttachmentKind.IMAGE) }
    }
    val wav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importMedia(it, ChatAttachmentKind.AUDIO) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        cameraUri = null
        if (uri != null) {
            if (success) viewModel.importMedia(uri, ChatAttachmentKind.IMAGE, temporarySource = true)
            else runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording(recordingName) else onError(R.string.ai_mic_permission)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.cancelRecording() }

    val canAddImage = state.attachments.count { it.kind == ChatAttachmentKind.IMAGE } < ChatMediaStore.MAX_IMAGES
    val canAddAudio = state.attachments.none { it.kind == ChatAttachmentKind.AUDIO }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled && (canAddImage || canAddAudio)) {
            Icon(Icons.Rounded.Add, stringResource(R.string.ai_add_attachment))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_pick_image)) },
                leadingIcon = { Icon(Icons.Rounded.Image, null) },
                enabled = canAddImage,
                onClick = {
                    expanded = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_camera_image)) },
                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                enabled = canAddImage,
                onClick = {
                    expanded = false
                    val result = runCatching {
                        val dir = File(context.cacheDir, "ai-camera").also { it.mkdirs() }
                        val file = File.createTempFile("photo-", ".jpg", dir)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraUri = uri
                        camera.launch(uri)
                    }
                    if (result.isFailure) {
                        cameraUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                        cameraUri = null
                        onError(R.string.ai_camera_unavailable)
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_record_audio)) },
                leadingIcon = { Icon(Icons.Rounded.Mic, null) },
                enabled = canAddAudio,
                onClick = {
                    expanded = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording(recordingName)
                    } else {
                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_pick_wav)) },
                leadingIcon = { Icon(Icons.Rounded.AudioFile, null) },
                enabled = canAddAudio,
                onClick = {
                    expanded = false
                    wav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/vnd.wave"))
                }
            )
        }
    }
}

@Composable
internal fun ChatMediaPreview(
    attachment: ChatAttachment,
    file: File,
    onRemove: (() -> Unit)? = null
) {
    when (attachment.kind) {
        ChatAttachmentKind.IMAGE -> Box {
            AsyncImage(
                model = file,
                contentDescription = stringResource(R.string.ai_image_attachment),
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = if (onRemove == null) 240.dp else 88.dp)
                    .height(if (onRemove == null) 150.dp else 76.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            if (onRemove != null) {
                Surface(Modifier.align(Alignment.TopEnd), shape = MaterialTheme.shapes.extraLarge) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.ai_remove_attachment), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        ChatAttachmentKind.AUDIO -> {
            var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
            var playing by remember(file) { mutableStateOf(false) }
            fun release() {
                player?.release()
                player = null
                playing = false
            }
            DisposableEffect(file) { onDispose { release() } }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(
                    Modifier.padding(end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (playing) {
                            release()
                        } else {
                            val media = MediaPlayer()
                            player = media
                            runCatching {
                                media.setDataSource(file.absolutePath)
                                media.setOnPreparedListener { it.start(); playing = true }
                                media.setOnCompletionListener { release() }
                                media.setOnErrorListener { _, _, _ -> release(); true }
                                media.prepareAsync()
                            }.onFailure { release() }
                        }
                    }, enabled = playing || player == null) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (playing) R.string.ai_pause_audio else R.string.ai_play_audio)
                        )
                    }
                    Text(
                        attachment.name,
                        Modifier.widthIn(max = 122.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (onRemove != null) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.ai_remove_attachment), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@StringRes
internal fun ChatMediaError.label(): Int = when (this) {
    ChatMediaError.IMAGE -> R.string.ai_image_error
    ChatMediaError.WAV_FORMAT -> R.string.ai_wav_error
    ChatMediaError.AUDIO_TOO_LONG -> R.string.ai_audio_too_long
    ChatMediaError.RECORDING -> R.string.ai_recording_error
}
