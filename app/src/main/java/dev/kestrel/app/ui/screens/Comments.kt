package dev.kestrel.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Comment
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.LoadMoreEffect
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.launch

/** Loads comments: from the account when signed in (so you can post/reply/delete), otherwise anonymously. */
private class CommentsLoader(val videoId: String) {
    val signedIn = Graph.account.isSignedIn
    var createParams: String? = null

    suspend fun first(): Pair<List<Comment>, Any?> {
        if (signedIn) {
            try {
                val p = Graph.innerTube.comments(videoId)
                createParams = p.createParams
                if (p.comments.isNotEmpty() || p.createParams != null) return p.comments to p.next
            } catch (_: Exception) {
                // Fall back to anonymous comments below.
            }
        }
        val p = NpRepo.comments(videoId)
        return p.items to p.next
    }

    suspend fun more(token: Any): Pair<List<Comment>, Any?> = when (token) {
        is String -> Graph.innerTube.commentsMore(token).let { p ->
            if (createParams == null) createParams = p.createParams
            p.comments to p.next
        }
        is NpRepo.CommentState -> NpRepo.commentsMore(token).let { it.items to it.next }
        else -> emptyList<Comment>() to null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(videoId: String, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val loader = remember(videoId) { CommentsLoader(videoId) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var next by remember { mutableStateOf<Any?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var posting by remember { mutableStateOf(false) }
    val replies = remember { mutableStateMapOf<String, List<Comment>>() }
    val listState = rememberLazyListState()

    fun reload() {
        loading = true; error = null
        scope.launch {
            try {
                val (c, n) = loader.first(); comments = c; next = n
            } catch (e: Exception) { error = friendlyError(e) }
            loading = false
        }
    }
    LaunchedEffect(videoId) { reload() }
    LoadMoreEffect(listState) {
        val n = next ?: return@LoadMoreEffect
        if (loading) return@LoadMoreEffect
        loading = true
        scope.launch {
            try {
                val (c, nn) = loader.more(n)
                val ids = comments.map { it.id }.toHashSet()
                comments = comments + c.filter { it.id !in ids }; next = nn
            } catch (e: Exception) { next = null }
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).imePadding()) {
            Text("Comments", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (error != null) item { Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (!loading && comments.isEmpty() && error == null) item { Text("No comments yet (or comments are turned off).", Modifier.padding(16.dp)) }
                items(comments, key = { it.id }) { c ->
                    CommentView(c,
                        onReply = if (loader.signedIn && c.replyParams != null) ({ replyTo = c }) else null,
                        onDelete = if (c.deleteAction != null) ({
                            scope.launch {
                                runCatching { Graph.innerTube.deleteComment(c.deleteAction!!) }
                                    .onSuccess { comments = comments.filterNot { it.id == c.id }; nav.message("Comment deleted") }
                                    .onFailure { nav.message(friendlyError(it)) }
                            }
                        }) else null,
                    )
                    val shown = replies[c.id]
                    if (shown != null) {
                        shown.forEach { rc -> CommentView(rc, Modifier.padding(start = 48.dp), null, if (rc.deleteAction != null) ({
                            scope.launch {
                                runCatching { Graph.innerTube.deleteComment(rc.deleteAction!!) }
                                    .onSuccess { replies[c.id] = shown.filterNot { it.id == rc.id }; nav.message("Reply deleted") }
                                    .onFailure { nav.message(friendlyError(it)) }
                            }
                        }) else null) }
                    } else if (c.replyCount > 0 && c.repliesPage != null) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { loader.more(c.repliesPage!!).first }
                                    .onSuccess { replies[c.id] = it }
                                    .onFailure { nav.message(friendlyError(it)) }
                            }
                        }, Modifier.padding(start = 56.dp)) { Text("${c.replyCount} replies") }
                    }
                }
                if (loading) item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
            }
            if (loader.signedIn) {
                replyTo?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replying to ${it.author}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { replyTo = null }) { Text("Cancel") }
                } }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(if (replyTo != null) "Add a reply…" else "Add a comment…") }, maxLines = 4)
                    IconButton(enabled = input.isNotBlank() && !posting, onClick = {
                        val text = input.trim()
                        val target = replyTo
                        posting = true
                        scope.launch {
                            try {
                                if (target != null) {
                                    Graph.innerTube.reply(target.replyParams!!, text)
                                    nav.message("Reply posted")
                                } else {
                                    val params = loader.createParams ?: error("Comments can't be posted on this video.")
                                    Graph.innerTube.postComment(params, text)
                                    nav.message("Comment posted")
                                }
                                input = ""; replyTo = null
                                reload()
                            } catch (e: Exception) {
                                nav.message(friendlyError(e))
                            }
                            posting = false
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, "Post") }
                }
            } else {
                Text("Sign in with Google to post comments.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CommentView(c: Comment, modifier: Modifier = Modifier, onReply: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Avatar(c.authorAvatar, 32)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${c.author}  •  ${c.published}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.text, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.likes.isNotBlank()) Text("👍 ${c.likes}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(1.dp).padding(horizontal = 8.dp))
                if (onReply != null) Text("Reply", Modifier.clickable(onClick = onReply).padding(8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
    }
}
