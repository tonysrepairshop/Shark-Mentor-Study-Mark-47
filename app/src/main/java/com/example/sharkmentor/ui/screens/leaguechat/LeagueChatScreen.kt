package com.example.sharkmentor.ui.screens.leaguechat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sharkmentor.core.badges.SharkBadgeShowcaseRow
import com.example.sharkmentor.core.util.SharkAvatar
import com.example.sharkmentor.ui.viewmodel.ChatFilterTab
import com.example.sharkmentor.ui.viewmodel.ChatImageCache
import com.example.sharkmentor.ui.viewmodel.DmThreadUi
import com.example.sharkmentor.ui.viewmodel.LeagueChatMessageUi
import com.example.sharkmentor.ui.viewmodel.LeagueChatMode
import com.example.sharkmentor.ui.viewmodel.LeagueChatUiState
import com.example.sharkmentor.ui.viewmodel.LeagueChatViewModel
import com.example.sharkmentor.ui.viewmodel.QuestionCardUi
import com.example.sharkmentor.ui.viewmodel.QuestionRewardTier
import com.example.sharkmentor.ui.viewmodel.SolutionReplyUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class LeagueChatDestination {
    data class GroupChat(val groupCode: String, val groupName: String) : LeagueChatDestination()
    data object DmInbox : LeagueChatDestination()
    data class PeerDm(
        val peerUid: String,
        val peerName: String,
        val peerAvatarBase64: String = "",
        val peerAvatarPresetId: String = ""
    ) : LeagueChatDestination()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeagueChatScreen(
    destination: LeagueChatDestination,
    myDisplayNameHint: String = "",
    onBackClick: () -> Unit,
    onOpenQuestionSolveScreen: ((QuestionCardUi) -> Unit)? = null,
    chatViewModel: LeagueChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val wallpaperStyle by remember(context) { ChatWallpaperPrefs.observeStyle(context) }.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var showQuestionPicker by rememberSaveable { mutableStateOf(false) }
    var showWallpaperPicker by rememberSaveable { mutableStateOf(false) }
    var solvingQuestion by remember { mutableStateOf<QuestionCardUi?>(null) }
    var zoomedImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var zoomedLocalPath by remember { mutableStateOf<String?>(null) }
    var messageInput by rememberSaveable { mutableStateOf("") }

    // Shared 1-second clock driving live 105-min question countdowns & tier chips
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LaunchedEffect(destination) {
        when (destination) {
            is LeagueChatDestination.GroupChat -> {
                chatViewModel.openGroupChat(destination.groupCode, destination.groupName, myDisplayNameHint)
            }
            is LeagueChatDestination.DmInbox -> {
                chatViewModel.openDmInbox(myDisplayNameHint)
            }
            is LeagueChatDestination.PeerDm -> {
                chatViewModel.openOrStartDmWithPeer(
                    peerUid = destination.peerUid,
                    peerName = destination.peerName,
                    peerAvatarBase64 = destination.peerAvatarBase64,
                    peerAvatarPresetId = destination.peerAvatarPresetId,
                    myDisplayNameHint = myDisplayNameHint
                )
            }
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            chatViewModel.clearStatusMessage()
        }
    }

    BackHandler {
        if (uiState.mode == LeagueChatMode.DM_THREAD && destination is LeagueChatDestination.DmInbox) {
            chatViewModel.openDmInbox(myDisplayNameHint)
        } else {
            onBackClick()
        }
    }

    val questionsById = remember(uiState.allMessages) {
        uiState.allMessages.mapNotNull { it.questionCard }.associateBy { it.messageId }
    }

    val pinnedOpenQuestions = remember(uiState.allMessages) {
        uiState.allMessages.mapNotNull { it.questionCard }
            .filter { it.status == "OPEN" || it.status == "PENDING_APPROVAL" }
            .sortedByDescending { it.createdAtMillis }
    }

    val displayedMessages = remember(uiState.allMessages, uiState.activeFilter, questionsById) {
        when (uiState.activeFilter) {
            ChatFilterTab.ALL -> uiState.allMessages
            ChatFilterTab.OPEN_QUESTIONS -> uiState.allMessages.filter { msg ->
                msg.questionCard?.status == "OPEN" ||
                    (msg.solutionReply != null && questionsById[msg.solutionReply.replyToQuestionId]?.status == "OPEN")
            }
            ChatFilterTab.PENDING_APPROVAL -> uiState.allMessages.filter { msg ->
                msg.questionCard?.status == "PENDING_APPROVAL" ||
                    msg.solutionReply?.approvalStatus == "PENDING"
            }
            ChatFilterTab.RESOLVED -> uiState.allMessages.filter { msg ->
                msg.questionCard?.status == "RESOLVED" ||
                    msg.solutionReply?.approvalStatus == "APPROVED"
            }
        }
    }

    LaunchedEffect(displayedMessages.size, uiState.mode) {
        if (displayedMessages.isNotEmpty() && uiState.mode != LeagueChatMode.DM_INBOX) {
            listState.scrollToItem((displayedMessages.size - 1).coerceAtLeast(0))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaperCanvas(
            styleOverride = wallpaperStyle,
            modifier = Modifier.fillMaxSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        when (uiState.mode) {
                            LeagueChatMode.GROUP_CHAT -> {
                                Column {
                                    Text(
                                        text = uiState.activeGroupName.ifBlank { "Grup & Soru Sohbeti" },
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Kod: ${uiState.activeGroupCode} • ${pinnedOpenQuestions.size} açık soru",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            LeagueChatMode.DM_INBOX -> {
                                Column {
                                    Text(
                                        text = "Mesaj İstekleri & DM",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val pendingCount = uiState.dmThreads.count { it.isIncomingRequest }
                                    Text(
                                        text = if (pendingCount > 0) "$pendingCount onay bekleyen istek" else "İstek onaylı direkt mesajlar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            LeagueChatMode.DM_THREAD -> {
                                val thread = uiState.activeDmThread
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.clickable(enabled = thread != null) {
                                        if (thread != null) {
                                            chatViewModel.openMemberProfile(
                                                studentId = thread.peerUid,
                                                fallbackName = thread.peerName,
                                                fallbackAvatarBase64 = thread.peerAvatarBase64,
                                                fallbackAvatarPresetId = thread.peerAvatarPresetId
                                            )
                                        }
                                    }
                                ) {
                                    SharkAvatar(
                                        avatarBase64 = thread?.peerAvatarBase64.orEmpty(),
                                        avatarPresetId = thread?.peerAvatarPresetId.orEmpty(),
                                        displayName = thread?.peerName ?: "Öğrenci",
                                        size = 34.dp
                                    )
                                    Column {
                                        Text(
                                            text = thread?.peerName ?: "Direkt Mesaj",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = when (thread?.status) {
                                                "ACCEPTED" -> "Onaylı DM"
                                                "PENDING" -> "Onay Bekleniyor"
                                                "REJECTED" -> "Reddedildi"
                                                else -> "İstek Onaylı DM"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (uiState.mode == LeagueChatMode.DM_THREAD && destination is LeagueChatDestination.DmInbox) {
                                    chatViewModel.openDmInbox(myDisplayNameHint)
                                } else {
                                    onBackClick()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showWallpaperPicker = true }) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Duvar Kağıdı Seç",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (uiState.mode == LeagueChatMode.GROUP_CHAT) {
                            val pendingDmCount = uiState.dmThreads.count { it.isIncomingRequest }
                            IconButton(onClick = { chatViewModel.openDmInbox(myDisplayNameHint) }) {
                                Box {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = "Mesaj İstekleri",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    if (pendingDmCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(14.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = pendingDmCount.coerceAtMost(9).toString(),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onError
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (uiState.mode == LeagueChatMode.DM_INBOX && destination is LeagueChatDestination.GroupChat) {
                            IconButton(
                                onClick = {
                                    chatViewModel.openGroupChat(destination.groupCode, destination.groupName, myDisplayNameHint)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forum,
                                    contentDescription = "Grup Sohbetine Dön",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    )
                )
            },
            bottomBar = {
                if (uiState.mode == LeagueChatMode.GROUP_CHAT || uiState.mode == LeagueChatMode.DM_THREAD) {
                    val activeThread = uiState.activeDmThread
                    val isDmLocked = uiState.mode == LeagueChatMode.DM_THREAD && activeThread?.status != "ACCEPTED"
                    if (isDmLocked && activeThread != null) {
                        DmApprovalBottomCard(
                            thread = activeThread,
                            onSendRequest = { note -> chatViewModel.sendDmRequestForActiveThread(note) },
                            onAccept = { chatViewModel.respondToDmRequest(activeThread, accept = true) },
                            onReject = { chatViewModel.respondToDmRequest(activeThread, accept = false) },
                            isSending = uiState.isSending
                        )
                    } else {
                        ChatComposerBar(
                            messageText = messageInput,
                            onMessageTextChange = { messageInput = it },
                            isSending = uiState.isSending,
                            onShareQuestionClick = { showQuestionPicker = true },
                            onSendClick = {
                                val text = messageInput
                                messageInput = ""
                                chatViewModel.sendTextMessage(text)
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            if (uiState.mode == LeagueChatMode.DM_INBOX) {
                DmInboxList(
                    threads = uiState.dmThreads,
                    onOpenThread = { chatViewModel.openDmThread(it, myDisplayNameHint) },
                    onAcceptRequest = { chatViewModel.respondToDmRequest(it, accept = true) },
                    onRejectRequest = { chatViewModel.respondToDmRequest(it, accept = false) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Top filter chips
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ChatFilterTab.entries) { tab ->
                            val selected = uiState.activeFilter == tab
                            FilterChip(
                                selected = selected,
                                onClick = { chatViewModel.selectFilter(tab) },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    // Pinned Open Questions Strip
                    if (pinnedOpenQuestions.isNotEmpty()) {
                        PinnedOpenQuestionsStrip(
                            questions = pinnedOpenQuestions,
                            nowMillis = nowMillis,
                            onQuestionTap = { tapped ->
                                val idx = displayedMessages.indexOfFirst { it.id == tapped.messageId }
                                if (idx >= 0) {
                                    coroutineScope.launch { listState.animateScrollToItem(idx) }
                                } else {
                                    chatViewModel.selectFilter(ChatFilterTab.ALL)
                                    val fallbackIdx = uiState.allMessages.indexOfFirst { it.id == tapped.messageId }
                                    if (fallbackIdx >= 0) {
                                        coroutineScope.launch { listState.animateScrollToItem(fallbackIdx) }
                                    }
                                }
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    when {
                        uiState.isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        uiState.errorMessage != null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = uiState.errorMessage.orEmpty(),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(20.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        displayedMessages.isEmpty() -> {
                            EmptyChatPlaceholder(
                                filterTab = uiState.activeFilter,
                                onShareQuestionClick = { showQuestionPicker = true }
                            )
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(displayedMessages, key = { _, m -> m.id }) { _, message ->
                                    val openSenderProfile: () -> Unit = {
                                        chatViewModel.openMemberProfile(
                                            studentId = message.senderId,
                                            fallbackName = message.senderName,
                                            fallbackAvatarBase64 = message.senderAvatarBase64,
                                            fallbackAvatarPresetId = message.senderAvatarPresetId,
                                            fallbackBadges = message.senderShowcaseBadges
                                        )
                                    }
                                    when {
                                        message.questionCard != null -> {
                                            QuestionMessageCard(
                                                message = message,
                                                question = message.questionCard,
                                                nowMillis = nowMillis,
                                                isSavedToErrorBank = uiState.savedQuestionIds.contains(message.questionCard.messageId),
                                                onSaveToErrorBank = { chatViewModel.saveQuestionToMyErrorBank(it) },
                                                onSenderClick = openSenderProfile,
                                                onImageClick = { bmp, localUri ->
                                                    zoomedImageBitmap = bmp
                                                    zoomedLocalPath = localUri
                                                },
                                                onSolveClick = { q ->
                                                    if (onOpenQuestionSolveScreen != null) {
                                                        onOpenQuestionSolveScreen(q)
                                                    } else {
                                                        solvingQuestion = q
                                                    }
                                                }
                                            )
                                        }
                                        message.solutionReply != null -> {
                                            val parentQ = questionsById[message.solutionReply.replyToQuestionId]
                                            SolutionReplyCard(
                                                message = message,
                                                solution = message.solutionReply,
                                                parentQuestion = parentQ,
                                                isQuestionOwner = (parentQ?.senderId == uiState.currentUid),
                                                onSenderClick = openSenderProfile,
                                                onApprove = {
                                                    if (parentQ != null) {
                                                        chatViewModel.approveSolution(parentQ, message.solutionReply)
                                                    }
                                                },
                                                onReject = {
                                                    if (parentQ != null) {
                                                        chatViewModel.rejectSolution(parentQ, message.solutionReply)
                                                    }
                                                },
                                                onImageClick = { bmp ->
                                                    zoomedImageBitmap = bmp
                                                    zoomedLocalPath = null
                                                }
                                            )
                                        }
                                        else -> {
                                            ChatTextBubble(
                                                message = message,
                                                onSenderClick = openSenderProfile
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Focus Lock Shield Overlay — strictly active during focus sessions
        FocusChatLockOverlay(onReturnToStudy = onBackClick)
    }

    if (showQuestionPicker) {
        ErrorBankQuestionPickerSheet(
            onDismiss = { showQuestionPicker = false },
            onSelectQuestion = { question ->
                chatViewModel.shareErrorQuestion(question)
            }
        )
    }

    val activeSolvingQuestion = solvingQuestion
    if (activeSolvingQuestion != null) {
        QuickSolutionComposerSheet(
            question = activeSolvingQuestion,
            nowMillis = nowMillis,
            onDismiss = { solvingQuestion = null },
            onSubmit = { text, imageUri ->
                chatViewModel.submitSolution(
                    question = activeSolvingQuestion,
                    solutionText = text,
                    solutionImageUri = imageUri
                )
                solvingQuestion = null
            }
        )
    }

    if (zoomedImageBitmap != null || !zoomedLocalPath.isNullOrBlank()) {
        Dialog(
            onDismissRequest = {
                zoomedImageBitmap = null
                zoomedLocalPath = null
            }
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = {
                                zoomedImageBitmap = null
                                zoomedLocalPath = null
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat")
                        }
                    }
                    val bmp = zoomedImageBitmap
                    val path = zoomedLocalPath
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Soru Görseli",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else if (!path.isNullOrBlank()) {
                        val model = remember(path) { File(path).takeIf { it.exists() } ?: path }
                        AsyncImage(
                            model = model,
                            contentDescription = "Soru Görseli",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
        }
    }

    if (showWallpaperPicker) {
        ChatWallpaperPickerSheet(
            currentStyle = wallpaperStyle,
            onSelectStyle = { style ->
                ChatWallpaperPrefs.setStyle(context, style)
            },
            onDismiss = { showWallpaperPicker = false }
        )
    }

    uiState.selectedMemberProfile?.let { member ->
        LeagueMemberProfileSheet(
            entry = member,
            existingDmStatus = uiState.dmStatusByPeerId[member.studentId],
            isBlocked = uiState.blockedUserIds.contains(member.studentId),
            onDismiss = { chatViewModel.dismissMemberProfile() },
            onSendDmRequest = { entry, note -> chatViewModel.sendDmRequest(entry, note) },
            onOpenAcceptedDm = { entry ->
                chatViewModel.dismissMemberProfile()
                val peerRealName = entry.rawName.ifBlank {
                    entry.displayName.removePrefix("Sen (").removeSuffix(")")
                }
                chatViewModel.openOrStartDmWithPeer(
                    peerUid = entry.studentId,
                    peerName = peerRealName,
                    peerAvatarBase64 = entry.avatarBase64,
                    peerAvatarPresetId = entry.avatarPresetId,
                    myDisplayNameHint = myDisplayNameHint
                )
            },
            onBlockUser = { entry, block -> chatViewModel.blockUser(entry, block) },
            onReportUser = { entry, reason, details -> chatViewModel.reportUser(entry, reason, details) }
        )
    }
}

@Composable
private fun PinnedOpenQuestionsStrip(
    questions: List<QuestionCardUi>,
    nowMillis: Long,
    onQuestionTap: (QuestionCardUi) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Sabitlenmiş Açık Sorular (${questions.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(questions, key = { it.messageId }) { q ->
                    val tier = q.currentRewardTier(nowMillis)
                    val rem = formatCountdownMmSs(q.remainingMillis(nowMillis))
                    Surface(
                        modifier = Modifier.clickable { onQuestionTap(q) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${tier.emoji} ${q.subject}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "• $rem • +${tier.points}P",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionMessageCard(
    message: LeagueChatMessageUi,
    question: QuestionCardUi,
    nowMillis: Long,
    isSavedToErrorBank: Boolean,
    onSaveToErrorBank: (QuestionCardUi) -> Unit,
    onSenderClick: () -> Unit,
    onImageClick: (ImageBitmap?, String?) -> Unit,
    onSolveClick: (QuestionCardUi) -> Unit
) {
    val context = LocalContext.current
    val remainingMs = question.remainingMillis(nowMillis)
    val rewardTier = question.currentRewardTier(nowMillis)
    val decodedBitmap = remember(question.imageBase64) {
        ChatImageCache.decodeBase64Bitmap(question.imageBase64)
    }
    val hasLocalFile = remember(question.localImageUri) {
        question.localImageUri.isNotBlank() && File(question.localImageUri).exists()
    }
    val timeLabel = remember(message.createdAtMillis) {
        SimpleDateFormat("HH:mm", Locale("tr")).format(Date(message.createdAtMillis))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        border = BorderStroke(
            width = 1.5.dp,
            color = when (question.status) {
                "RESOLVED" -> Color(0xFF10B981).copy(alpha = 0.6f)
                "PENDING_APPROVAL" -> Color(0xFFF59E0B).copy(alpha = 0.65f)
                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Sender Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSenderClick() }
                ) {
                    SharkAvatar(
                        avatarBase64 = message.senderAvatarBase64,
                        avatarPresetId = message.senderAvatarPresetId,
                        displayName = message.senderName,
                        size = 34.dp
                    )
                    Column {
                        Text(
                            text = if (message.isMine) "Sen (${message.senderName})" else message.senderName,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (message.senderShowcaseBadges.isNotEmpty()) {
                            SharkBadgeShowcaseRow(
                                badgeIds = message.senderShowcaseBadges,
                                compact = true,
                                badgeProgress = message.senderBadgeProgress
                            )
                        }
                    }
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Subject, Topic & Status Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = question.subject,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = question.topic,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                val statusLabel = when (question.status) {
                    "RESOLVED" -> "✅ Çözüldü"
                    "PENDING_APPROVAL" -> "⏳ Onay Bekliyor"
                    else -> "🔥 Açık Soru"
                }
                val statusColor = when (question.status) {
                    "RESOLVED" -> Color(0xFF10B981)
                    "PENDING_APPROVAL" -> Color(0xFFF59E0B)
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (question.noteText.isNotBlank()) {
                Text(
                    text = question.noteText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Question Image
            if (decodedBitmap != null) {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = "Paylaşılan Soru",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .clickable { onImageClick(decodedBitmap, null) }
                )
            } else if (hasLocalFile) {
                val localFile = remember(question.localImageUri) { File(question.localImageUri) }
                AsyncImage(
                    model = ImageRequest.Builder(context).data(localFile).crossfade(true).build(),
                    contentDescription = "Paylaşılan Soru",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImageClick(null, question.localImageUri) }
                )
            }

            // 105-Minute Countdown & Reward Tier Banner
            if (question.status != "RESOLVED") {
                val tierColor = when (rewardTier) {
                    QuestionRewardTier.GOLD -> Color(0xFFD97706)
                    QuestionRewardTier.SILVER -> Color(0xFF0284C7)
                    QuestionRewardTier.BRONZE -> Color(0xFF9333EA)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = tierColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, tierColor.copy(alpha = 0.36f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rewardTier.summaryBadge(),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                            color = tierColor,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = tierColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (remainingMs > 0L) formatCountdownMmSs(remainingMs) else "Bronz Sabit",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = tierColor
                            )
                        }
                    }
                }

                Button(
                    onClick = { onSolveClick(question) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("✏️ Soruyu Çöz", fontWeight = FontWeight.Bold)
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "✅ Çözüm Onaylandı${question.solverName?.takeIf { it.isNotBlank() }?.let { " • Çözen: $it" } ?: ""}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF059669),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = { onSaveToErrorBank(question) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSavedToErrorBank) {
                        Color(0xFF10B981).copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
            ) {
                Icon(
                    imageVector = if (isSavedToErrorBank) Icons.Default.Check else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (isSavedToErrorBank) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isSavedToErrorBank) "✅ Hata Bankana Kaydedildi" else "📥 Hata Bankama Kaydet",
                    color = if (isSavedToErrorBank) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun SolutionReplyCard(
    message: LeagueChatMessageUi,
    solution: SolutionReplyUi,
    parentQuestion: QuestionCardUi?,
    isQuestionOwner: Boolean,
    onSenderClick: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onImageClick: (ImageBitmap) -> Unit
) {
    val decodedBitmap = remember(solution.solutionImageBase64) {
        ChatImageCache.decodeBase64Bitmap(solution.solutionImageBase64)
    }
    val statusColor = when (solution.approvalStatus) {
        "APPROVED" -> Color(0xFF10B981)
        "REJECTED" -> MaterialTheme.colorScheme.error
        else -> Color(0xFFF59E0B)
    }
    val statusLabel = when (solution.approvalStatus) {
        "APPROVED" -> "✅ Onaylandı: +${solution.escrowPoints} Puan & +${solution.escrowBonusMinutes} dk"
        "REJECTED" -> "❌ Reddedildi"
        else -> "⏳ Onay Bekleyen Puan: +${solution.escrowPoints} P & +${solution.escrowBonusMinutes} dk"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f)
        ),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { onSenderClick() }
                ) {
                    SharkAvatar(
                        avatarBase64 = message.senderAvatarBase64,
                        avatarPresetId = message.senderAvatarPresetId,
                        displayName = message.senderName,
                        size = 28.dp
                    )
                    Text(
                        text = "${message.senderName} • Çözüm Önerisi",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (parentQuestion != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = parentQuestion.subject,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (solution.solutionText.isNotBlank()) {
                Text(
                    text = solution.solutionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (decodedBitmap != null) {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = "Çözüm Görseli",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onImageClick(decodedBitmap) }
                )
            }

            // Escrow Status Pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = statusColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // Owner Approval Buttons
            if (isQuestionOwner && solution.approvalStatus == "PENDING" && parentQuestion?.status != "RESOLVED") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "✗ Reddet",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("✓ Onayla", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTextBubble(
    message: LeagueChatMessageUi,
    onSenderClick: () -> Unit
) {
    val timeLabel = remember(message.createdAtMillis) {
        SimpleDateFormat("HH:mm", Locale("tr")).format(Date(message.createdAtMillis))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.isMine) {
            SharkAvatar(
                avatarBase64 = message.senderAvatarBase64,
                avatarPresetId = message.senderAvatarPresetId,
                displayName = message.senderName,
                size = 30.dp,
                modifier = Modifier.clickable { onSenderClick() }
            )
            Spacer(Modifier.width(8.dp))
        }

        Surface(
            modifier = Modifier.widthIn(max = 290.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isMine) 16.dp else 4.dp,
                bottomEnd = if (message.isMine) 4.dp else 16.dp
            ),
            color = if (message.isMine) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            },
            border = BorderStroke(
                1.dp,
                if (message.isMine) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!message.isMine) {
                    Column(
                        modifier = Modifier.clickable { onSenderClick() },
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (message.senderShowcaseBadges.isNotEmpty()) {
                            SharkBadgeShowcaseRow(
                                badgeIds = message.senderShowcaseBadges,
                                compact = true,
                                badgeProgress = message.senderBadgeProgress
                            )
                        }
                    }
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isMine) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun ChatComposerBar(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    isSending: Boolean,
    onShareQuestionClick: () -> Unit,
    onSendClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onShareQuestionClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "+ Soru Paylaş",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageTextChange,
                    placeholder = { Text("Mesaj yaz...") },
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = CircleShape,
                    color = if (messageText.isNotBlank() && !isSending) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(enabled = messageText.isNotBlank() && !isSending) { onSendClick() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Gönder",
                                tint = if (messageText.isNotBlank()) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DmApprovalBottomCard(
    thread: DmThreadUi,
    onSendRequest: (String) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    isSending: Boolean
) {
    var noteInput by rememberSaveable(thread.threadId) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                thread.status == "NONE" -> {
                    Text(
                        text = "📩 İstek Onaylı Direkt Mesaj",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it.take(140) },
                        label = { Text("Mesaj isteği notu") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSendRequest(noteInput) },
                        enabled = !isSending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Mesaj İsteği Gönder", fontWeight = FontWeight.Bold)
                    }
                }
                thread.isIncomingRequest -> {
                    Text(
                        text = "${thread.peerName} sana mesaj isteği gönderdi",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    if (thread.requestNote.isNotBlank()) {
                        Text(
                            text = "\"${thread.requestNote}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Reddet", color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = onAccept,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Kabul Et", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                thread.status == "REJECTED" -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = "Bu mesaj isteği reddedildi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Karşı taraf mesaj isteğini onayladığında mesajlaşma ve soru paylaşımı açılacak.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DmInboxList(
    threads: List<DmThreadUi>,
    onOpenThread: (DmThreadUi) -> Unit,
    onAcceptRequest: (DmThreadUi) -> Unit,
    onRejectRequest: (DmThreadUi) -> Unit,
    modifier: Modifier = Modifier
) {
    val incoming = threads.filter { it.isIncomingRequest }
    val accepted = threads.filter { it.status == "ACCEPTED" }
    val outgoing = threads.filter { it.status == "PENDING" && !it.isIncomingRequest }

    if (threads.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "Henüz mesaj isteği veya DM yok",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Shark Ligi'nden bir öğrencinin profiline dokunarak istek onaylı soru/mesaj isteği başlatabilirsin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (incoming.isNotEmpty()) {
            item {
                Text(
                    text = "GELEN MESAJ İSTEKLERİ (${incoming.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(incoming, key = { "in_${it.threadId}" }) { thread ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SharkAvatar(
                                avatarBase64 = thread.peerAvatarBase64,
                                avatarPresetId = thread.peerAvatarPresetId,
                                displayName = thread.peerName,
                                size = 40.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = thread.peerName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = thread.requestNote.ifBlank { "Direkt mesaj isteği" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onRejectRequest(thread) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Reddet", color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = { onAcceptRequest(thread) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Kabul Et", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (accepted.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ONAYLI SOHBETLER (${accepted.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(accepted, key = { "ok_${it.threadId}" }) { thread ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenThread(thread) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SharkAvatar(
                            avatarBase64 = thread.peerAvatarBase64,
                            avatarPresetId = thread.peerAvatarPresetId,
                            displayName = thread.peerName,
                            size = 42.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = thread.peerName,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = thread.lastMessageText.ifBlank { "Sohbeti açmak için dokun" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (outgoing.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "GÖNDERİLEN İSTEKLER (${outgoing.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(outgoing, key = { "out_${it.threadId}" }) { thread ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenThread(thread) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SharkAvatar(
                            avatarBase64 = thread.peerAvatarBase64,
                            avatarPresetId = thread.peerAvatarPresetId,
                            displayName = thread.peerName,
                            size = 36.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = thread.peerName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "⏳ Karşı tarafın onayı bekleniyor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSolutionComposerSheet(
    question: QuestionCardUi,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onSubmit: (solutionText: String, solutionImageUri: Uri?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var solutionText by rememberSaveable(question.messageId) { mutableStateOf("") }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) attachedImageUri = uri
    }

    val tier = question.currentRewardTier(nowMillis)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "✏️ Soruyu Çöz (${question.subject})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Text(
                    text = "Aktif Ödül Dilimi: ${tier.summaryBadge()}\nOnaylandığında puanlar hesabına aktarılır.",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }

            OutlinedTextField(
                value = solutionText,
                onValueChange = { solutionText = it },
                label = { Text("Çözüm Adımları & İpucu") },
                placeholder = { Text("Çözüm mantığını adım adım yaz...") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (attachedImageUri == null) "Çözüm Fotoğrafı Ekle" else "Fotoğraf Değiştir ✓",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = { onSubmit(solutionText, attachedImageUri) },
                    enabled = solutionText.isNotBlank() || attachedImageUri != null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Çözümü Gönder", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(
    filterTab: ChatFilterTab,
    onShareQuestionClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
                Text(
                    text = if (filterTab == ChatFilterTab.ALL) {
                        "Sohbeti İlk Başlatan Sen Ol"
                    } else {
                        "${filterTab.label} bulunamadı"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Hata Kumbarası'ndan soru paylaşarak grup arkadaşlarınla 105 dakikalık hızlı çözüm yarışını başlatabilirsin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = onShareQuestionClick,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ Hata Kumbarası'ndan Soru Paylaş")
                }
            }
        }
    }
}

private fun formatCountdownMmSs(remainingMs: Long): String {
    val totalSecs = (remainingMs / 1_000L).coerceAtLeast(0L)
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
