package com.ritesh.cashiro.presentation.ui.features.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.ritesh.cashiro.R
import com.ritesh.cashiro.data.database.entity.ChatMessage
import com.ritesh.cashiro.data.database.entity.ChatSession
import com.ritesh.cashiro.data.model.ChatAttachment
import com.ritesh.cashiro.data.preferences.AiDataAccess
import com.ritesh.cashiro.data.preferences.AiPromptPreferences
import com.ritesh.cashiro.data.repository.ApiAiModel
import com.ritesh.cashiro.data.service.ApiLlmError
import com.ritesh.cashiro.presentation.ui.features.settings.displayLabel
import com.ritesh.cashiro.presentation.ui.features.settings.AiPromptSettingsDialog
import com.ritesh.cashiro.utils.TokenUtils
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val selection by chatViewModel.modelSelection.collectAsStateWithLifecycle()
    val sessions by chatViewModel.chatSessions.collectAsStateWithLifecycle()
    val sessionId by chatViewModel.currentSessionId.collectAsStateWithLifecycle()
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val response by chatViewModel.currentResponse.collectAsStateWithLifecycle()
    val mediaState by chatViewModel.mediaState.collectAsStateWithLifecycle()
    val settings by chatViewModel.aiPromptPreferences.collectAsStateWithLifecycle(initialValue = AiPromptPreferences())
    val tokenInfoEnabled by chatViewModel.isTokenInfoEnabled.collectAsStateWithLifecycle(initialValue = false)
    val stats by chatViewModel.chatStats.collectAsStateWithLifecycle()

    val selected = selection.selected
    val apiModel = selected as? ApiAiModel
    val access = if (apiModel != null && !apiModel.shareFinancialData) emptySet() else settings.dataAccess
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    var editId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var showPromptSettings by rememberSaveable { mutableStateOf(false) }
    var mediaNotice by remember { mutableStateOf<Int?>(null) }
    val selectSession: (String) -> Unit = { id ->
        chatViewModel.loadSession(id)
        scope.launch { drawerState.close() }
    }
    val newChat: () -> Unit = {
        chatViewModel.startNewChat()
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(sessionId, messages.size, uiState.isLoading, response.length) {
        val lastIndex = messages.lastIndex + if (uiState.isLoading) 1 else 0
        val layout = listState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastIndex >= 0 && !listState.isScrollInProgress && lastVisible >= layout.totalItemsCount - 2) {
            listState.requestScrollToItem(lastIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState) {
                ChatHistoryPanel(
                    sessions = sessions,
                    sessionId = sessionId,
                    onNewChat = newChat,
                    onSelect = selectSession,
                    onDelete = chatViewModel::deleteSession,
                    onClose = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = selected?.name ?: stringResource(R.string.ai_assistant),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (apiModel == null) stringResource(R.string.ai_chat_preview_subtitle)
                                    else stringResource(R.string.ai_api_mode, apiModel.provider.displayLabel()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ai_chat_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.History, stringResource(R.string.chat_history))
                        }
                        IconButton(onClick = newChat) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.new_chat))
                        }
                        IconButton(onClick = { showPromptSettings = true }) {
                            Icon(Icons.Rounded.Tune, stringResource(R.string.ai_prompt_settings))
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    val displayedMessages = messages.filter { it.sessionId == sessionId }
                    val onCopy: (String) -> Unit = { text ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copied_text), text))
                    }
                    if (displayedMessages.isEmpty() && !uiState.isLoading) {
                        ChatWelcome(
                            hasModel = selected != null,
                            access = access,
                            onPickModel = onNavigateToSettings,
                            onSuggestion = { input = it },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                items(displayedMessages, key = { it.id }) { message ->
                                    ChatMessageRow(
                                        message = message,
                                        mediaFile = { chatViewModel.mediaFile(it) },
                                        editable = selected != null && !uiState.isLoading &&
                                            (message.attachments.isEmpty() || selected.supportsMedia),
                                        canRegenerate = apiModel == null ||
                                            message.aiHistoryScope == apiModel.historyScope(settings.historyScope),
                                        onCopy = { onCopy(message.message) },
                                        onEdit = {
                                            editId = message.id
                                            input = message.message
                                        },
                                        onDelete = { chatViewModel.deleteMessage(message.id) },
                                        onRegenerate = { chatViewModel.regenerateMessage(message.id) }
                                    )
                                }
                                if (uiState.isLoading) {
                                    item(key = "live-response") {
                                        if (response.isBlank()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Text(stringResource(R.string.ai_chat_generating), style = MaterialTheme.typography.bodySmall)
                                            }
                                        } else {
                                            AssistantReply(text = response, modelName = selected?.name)
                                        }
                                    }
                                }
                            }

                            if (listState.canScrollForward) {
                                FilledIconButton(
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                                    onClick = {
                                        scope.launch {
                                            val last = listState.layoutInfo.totalItemsCount - 1
                                            if (last >= 0) listState.animateScrollToItem(last)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.ai_chat_jump_to_latest))
                                }
                            }
                        }
                    }

                    val mediaError = mediaNotice ?: mediaState.error?.label()
                    val errorText = (mediaError ?: uiState.apiError?.label())?.let { stringResource(it) } ?: uiState.error
                    errorText?.let { error ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = {
                                    mediaNotice = null
                                    chatViewModel.clearMediaError()
                                    chatViewModel.clearError()
                                }) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.dismiss))
                                }
                            }
                        }
                    }

                    if (selected != null) {
                        if (tokenInfoEnabled && apiModel == null) {
                            ChatTokenDetails(selected.name, stats)
                        }
                        if (mediaState.recording) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.ai_recording_in_progress), Modifier.weight(1f))
                                TextButton(onClick = chatViewModel::cancelRecording) {
                                    Text(stringResource(R.string.cancel))
                                }
                                TextButton(onClick = chatViewModel::finishRecording) {
                                    Text(stringResource(R.string.ai_attach_recording))
                                }
                            }
                        }
                        val editingMessage = displayedMessages.firstOrNull { it.id == editId }
                        val attachments = editingMessage?.attachments ?: mediaState.attachments
                        ChatComposer(
                            input = input,
                            editing = editId != null,
                            generating = uiState.isLoading,
                            preparingMedia = mediaState.busy,
                            attachments = attachments,
                            mediaPreview = {
                                ChatMediaPreview(
                                    attachment = it,
                                    file = chatViewModel.mediaFile(it, draft = editingMessage == null),
                                    onRemove = if (!uiState.isLoading && editingMessage == null) ({
                                        chatViewModel.removeDraft(it)
                                    }) else null
                                )
                            },
                            attachmentButton = if (selected.supportsMedia && editId == null) ({
                                ChatMediaButton(
                                    chatViewModel,
                                    mediaState,
                                    enabled = !uiState.isLoading && !mediaState.busy,
                                    onError = { mediaNotice = it }
                                )
                            }) else null,
                            accessCount = access.size,
                            apiModel = apiModel != null,
                            apiSharing = apiModel?.shareFinancialData == true,
                            onInput = { input = it },
                            onPermissions = { showPromptSettings = true },
                            onCancelEdit = {
                                editId = null
                                input = ""
                            },
                            onStop = chatViewModel::stopGeneration,
                            onSend = {
                                val id = editId
                                if (id == null) chatViewModel.sendMessage(input) else chatViewModel.editMessage(id, input)
                                input = ""
                                editId = null
                            }
                        )
                    } else if (displayedMessages.isNotEmpty()) {
                        TextButton(onClick = onNavigateToSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(stringResource(R.string.ai_select_model))
                        }
                    }
                }
            }
        }
    }

    if (showPromptSettings) {
        AiPromptSettingsDialog(
            settings = settings,
            onDismiss = { showPromptSettings = false },
            onSave = chatViewModel::saveAiPromptPreferences
        )
    }
}

@Composable
private fun ChatWelcome(
    hasModel: Boolean,
    access: Set<AiDataAccess>,
    onPickModel: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier
) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
            Text(
                stringResource(if (hasModel) R.string.ai_chat_empty_title else R.string.model_required),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            if (hasModel) {
                val ideas = access.mapNotNull { it.suggestion() }.take(3).ifEmpty { listOf(R.string.ai_example_help) }
                ideas.forEach { id ->
                    val suggestion = stringResource(id)
                    OutlinedButton(onClick = { onSuggestion(suggestion) }) { Text(suggestion) }
                }
            } else {
                Text(
                    stringResource(R.string.ai_select_model_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onPickModel) { Text(stringResource(R.string.go_to_settings)) }
            }
        }
    }
}

@StringRes
private fun AiDataAccess.suggestion(): Int? = when (this) {
    AiDataAccess.MONTHLY_SUMMARY -> R.string.ai_example_month
    AiDataAccess.RECENT_TRANSACTIONS -> R.string.ai_example_transactions
    AiDataAccess.ACCOUNTS -> R.string.ai_example_accounts
    AiDataAccess.BUDGETS -> R.string.ai_example_budgets
    AiDataAccess.SUBSCRIPTIONS -> R.string.ai_example_subscriptions
    AiDataAccess.CATEGORIES -> null
}

@Composable
private fun ChatComposer(
    input: String,
    editing: Boolean,
    generating: Boolean,
    preparingMedia: Boolean,
    attachments: List<ChatAttachment>,
    mediaPreview: @Composable (ChatAttachment) -> Unit,
    attachmentButton: (@Composable () -> Unit)?,
    accessCount: Int,
    apiModel: Boolean,
    apiSharing: Boolean,
    onInput: (String) -> Unit,
    onPermissions: () -> Unit,
    onCancelEdit: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.ai_chat_edit_message_hint), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancelEdit) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.cancel))
                }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                attachments.forEach { attachment -> mediaPreview(attachment) }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (attachmentButton != null) {
                    Box(Modifier.padding(start = 4.dp)) { attachmentButton() }
                }
                TextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = { Text(stringResource(R.string.ai_chat_input_hint)) },
                    readOnly = generating,
                    minLines = 1,
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                FilledIconButton(
                    onClick = if (generating) onStop else onSend,
                    enabled = generating || (!preparingMedia && (input.isNotBlank() || attachments.isNotEmpty()))
                ) {
                    if (generating) {
                        Icon(Icons.Rounded.Stop, stringResource(R.string.ai_chat_stop_generation))
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.send))
                    }
                }
            }
        }
        TextButton(onClick = onPermissions, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(
                when {
                    apiModel && !apiSharing -> stringResource(R.string.ai_api_no_data)
                    accessCount == 0 -> stringResource(R.string.ai_no_data_shared)
                    else -> stringResource(R.string.ai_data_shared_count, accessCount)
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    mediaFile: (ChatAttachment) -> File,
    editable: Boolean,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    if (message.isUser) {
        Box(Modifier.fillMaxWidth().padding(start = 40.dp), contentAlignment = Alignment.TopEnd) {
            val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp)
            Column(
                modifier = Modifier.clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .combinedClickable(onClick = { showActions = true }, onLongClick = { showActions = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.attachments.forEach { attachment ->
                    ChatMediaPreview(attachment = attachment, file = mediaFile(attachment))
                }
                if (message.message.isNotBlank()) {
                    Text(
                        text = message.message,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    enabled = message.message.isNotBlank(),
                    onClick = {
                        showActions = false
                        onCopy()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    enabled = editable,
                    onClick = {
                        showActions = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = {
                        showActions = false
                        onDelete()
                    }
                )
            }
        }
    } else {
        Column {
            AssistantReply(text = message.message, modelName = message.modelName)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onRegenerate, enabled = editable && canRegenerate) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.regenerate), modifier = Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { showActions = true }) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = {
                                showActions = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantReply(text: String, modelName: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(modelName ?: stringResource(R.string.local_ai_assistant), style = MaterialTheme.typography.labelLarge)
        }
        SelectionContainer {
            Markdown(content = text, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ChatHistoryPanel(
    sessions: List<ChatSession>,
    sessionId: String,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    val matches = sessions.filter { it.title.contains(query, ignoreCase = true) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Column(Modifier.fillMaxHeight().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_history), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
        }
        Button(onClick = onNewChat, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.new_chat), Modifier.padding(start = 6.dp))
        }
        if (sessions.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_chats)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
        }
        if (matches.isEmpty()) {
            Text(
                stringResource(if (sessions.isEmpty()) R.string.ai_chat_history_empty else R.string.ai_chat_history_no_results),
                Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(matches, key = { it.id }) { session ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                dateFormat.format(Date(session.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    selected = session.id == sessionId,
                    onClick = { onSelect(session.id) },
                    badge = {
                        IconButton(onClick = { pendingDelete = session }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                        }
                    }
                )
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ai_chat_delete_session_title)) },
            text = { Text(stringResource(R.string.ai_chat_delete_session_description)) },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(session.id) }) { Text(stringResource(R.string.delete)) }
            }
        )
    }
}

@Composable
private fun ChatTokenDetails(modelName: String, stats: ChatStats) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(
                    R.string.tokens_usage_format,
                    TokenUtils.formatNumber(stats.estimatedTokens),
                    TokenUtils.formatNumber(stats.maxTokens)
                ),
                color = if (stats.contextUsagePercent >= 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            Text(stringResource(R.string.ai_model_messages, modelName, stats.messageCount), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.system_tokens_format, TokenUtils.formatNumber(stats.systemPromptTokens)),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@StringRes
private fun ApiLlmError.label(): Int = when (this) {
    ApiLlmError.AUTH -> R.string.ai_api_error_auth
    ApiLlmError.BILLING -> R.string.ai_api_error_billing
    ApiLlmError.NOT_FOUND -> R.string.ai_api_error_missing
    ApiLlmError.RATE_LIMIT -> R.string.ai_api_error_rate
    ApiLlmError.SERVER -> R.string.ai_api_error_server
    ApiLlmError.RESPONSE -> R.string.ai_api_error_response
    ApiLlmError.NETWORK -> R.string.ai_api_error_network
    ApiLlmError.EMPTY -> R.string.ai_api_error_empty
}
