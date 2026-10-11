package fr.appartfinder.freetube.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.appartfinder.freetube.R

/** Icon-only button: [description] is what TalkBack reads, so it is mandatory. */
@Composable
fun IconAction(@DrawableRes icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), contentDescription = description) }
}

/** The trailing "more" button of a row; [content] receives `close` to dismiss the menu after a choice. */
@Composable
fun OverflowMenu(description: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_ellipsis_vertical, description) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) { content { open = false } }
    }
}

/** A menu entry with its icon. [destructive] entries go last and take the accent color. */
@Composable
fun MenuAction(label: String, @DrawableRes icon: Int, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = if (destructive) {
        MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error)
    } else {
        MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        onClick = onClick,
        enabled = enabled,
        colors = colors,
    )
}

/** Thumbnail or cover. The music icon underneath shows while loading and when there is no image. */
@Composable
fun Artwork(model: Any?, modifier: Modifier, shape: Shape = MaterialTheme.shapes.extraSmall, iconSize: Dp = 20.dp) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_music),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        AsyncImage(model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/** One muted icon, one sentence, at most one action. */
@Composable
fun EmptyState(@DrawableRes icon: Int, message: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, onClick) -> FilledTonalButton(onClick = onClick, shape = MaterialTheme.shapes.small) { Text(label) } }
    }
}
