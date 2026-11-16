package dev.kestrel.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.kestrel.app.Graph
import dev.kestrel.app.data.innertube.InnerTube
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Live chat of a stream: polls YouTube for new messages; signed in, you can also write. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveChatSheet(firstToken: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<InnerTube.ChatMsg>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sendParams by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    LaunchedEffect(firstToken) {
        var token: String? = firstToken
        while (token != null) {
            try {
                val page = Graph.innerTube.liveChat(token)
                error = null
                // Only the first reply has the send box; later polls leave it out.
                page.sendParams?.let { sendParams = it }
                page.notice?.let { notice = it }
                if (page.items.isNotEmpty()) {
                    val have = messages.map { it.id }.toHashSet()
                    // Keep the newest 300.
                    messages = (messages + page.items.filter { it.id !in have }).takeLast(300)
                    if (list.firstVisibleItemIndex <= 2) list.scrollToItem(0)
                }
                token = page.next
                delay(page.waitMs.coerceIn(1_500, 10_000))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
                delay(5_000)
            }
        }
        if (messages.isEmpty()) error = "The chat is closed."
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            Text("Live chat", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) Text(error ?: "Loading chat…", Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Newest at the bottom, like YouTube.
                LazyColumn(Modifier.fillMaxWidth(), state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(messages.asReversed(), key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Avatar(m.avatar, 24)
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)) { append(m.author) }
                                append("  ")
                                append(m.text)
                            }, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (error != null && messages.isNotEmpty()) Text(error!!, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            val params = sendParams
            if (params == null) Text(notice ?: if (Graph.account.isSignedIn) "You can't chat in this stream." else "Sign in to chat.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it.take(200) }, Modifier.weight(1f), placeholder = { Text("Chat…") }, singleLine = true)
                IconButton(enabled = draft.isNotBlank() && !sending, onClick = {
                    val text = draft.trim()
                    sending = true
                    scope.launch {
                        runCatching { Graph.innerTube.sendChat(params, text) }
                            .onSuccess { draft = "" }
                            .onFailure { error = friendlyError(it) }
                        sending = false
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}
