package dev.kestrel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kestrel.app.Graph

/** Bottom bar destinations. Subscriptions live in the top tabs now, like the current YouTube app. */
val bottomRoutes = listOf("home", "shorts", "library")
/** Routes that show the bottom bar. */
val chromeRoutes = setOf("home", "subs", "shorts", "library")

/** Icon-only bottom bar: Home, Shorts, You (account picture). */
@Composable
fun BottomBar(route: String, onSelect: (String) -> Unit) {
    val session by Graph.account.session.collectAsStateWithLifecycle()
    Column(Modifier.background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            val home = route == "home" || route == "subs"
            BarItem("Home", home, { onSelect("home") }) { Icon(if (home) Icons.Filled.Home else Icons.Outlined.Home, null, Modifier.size(28.dp)) }
            BarItem("Shorts", route == "shorts", { onSelect("shorts") }) {
                Icon(if (route == "shorts") Icons.Filled.Bolt else Icons.Outlined.Bolt, null, Modifier.size(28.dp))
            }
            val you = route == "library"
            BarItem("You", you, { onSelect("library") }) {
                val avatar = session?.avatar
                if (!avatar.isNullOrBlank()) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .then(if (you) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Avatar(avatar, if (you) 26 else 30) }
                } else Icon(if (you) Icons.Filled.Person else Icons.Outlined.Person, null, Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier.weight(1f).height(56.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected; role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** Text tabs under the main top bar (Home | Subs | Music | Live | More), underline on the active one. */
@Composable
fun TopTabs(tabs: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (key, label) ->
            val on = key == selected
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(key) }
                    .semantics { this.selected = on; role = Role.Tab }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

/** Top bar of the main screens: logo + name on the left, actions on the right. */
@Composable
fun BrandBar(actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dev.kestrel.app.ui.screens.Logo(30)
        Spacer(Modifier.width(8.dp))
        Text("Plover", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** Icon button with a clear touch target and label for screen readers. */
@Composable
fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(26.dp))
    }
}
