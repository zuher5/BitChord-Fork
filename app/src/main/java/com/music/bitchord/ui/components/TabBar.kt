package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.AppTab

/** One entry of the bottom tab bar. */
private data class TabSpec(
    val tab: AppTab,
    val label: String,
    val icon: ImageVector,
)

/**
 * The bar's translucent near-black. Not the surface token: the mockup's
 * rgba(20,20,21,.96) lets the page behind it faintly through, which a solid
 * surface cannot.
 */
private val BAR_BACKGROUND = Color(0xF2141415)

/** The mockup's inactive tab tint — a grey one step dimmer than onSurfaceVariant. */
private val INACTIVE_TINT = Color(0xFF8E8E93)

private val BAR_HEIGHT = 58.dp

/**
 * The full-width bottom tab bar: 58dp tall, a 1dp hairline on top, three
 * evenly spaced items. Replaces the floating pill — the mockup anchors the
 * bar to the screen's edges, and a pill under a full-width mini player read
 * as two different design languages.
 */
@Composable
fun TabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        TabSpec(AppTab.HOME, "Home", Icons.Outlined.Home),
        // Three ascending bars, as the mockup draws the library tab.
        TabSpec(AppTab.LOCAL, "Local", Icons.Outlined.BarChart),
        TabSpec(AppTab.SEARCH, "Search", Icons.Outlined.Search),
    )
    Column(modifier = modifier.background(BAR_BACKGROUND)) {
        // A border() on the bar would stroke all four sides; the mockup asks
        // for the hairline on top only.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
        ) {
            tabs.forEach { spec ->
                val isSelected = spec.tab == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(spec.tab) }
                        .padding(vertical = 6.dp),
                ) {
                    Icon(
                        spec.icon,
                        contentDescription = spec.label,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else INACTIVE_TINT,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        spec.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else INACTIVE_TINT,
                    )
                }
            }
        }
    }
}
