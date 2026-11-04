package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.AppTab

/** One entry of the bottom tab bar. */
private data class TabSpec(
    val tab: AppTab,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector,
)

/**
 * The floating bottom tab bar. A simple Surface rather than the upstream
 * glass nav bar: three tabs and a fixed background need no real-time blur,
 * and dropping it removes a whole dependency surface.
 */
@Composable
fun TabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        TabSpec(AppTab.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
        TabSpec(AppTab.LOCAL, "Local", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
        TabSpec(AppTab.SEARCH, "Search", Icons.Outlined.Search, Icons.Filled.Search),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        ) {
            tabs.forEach { spec ->
                val isSelected = spec.tab == selected
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(spec.tab) },
                ) {
                    Icon(
                        if (isSelected) spec.iconSelected else spec.icon,
                        contentDescription = spec.label,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        spec.label,
                        fontSize = 11.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
