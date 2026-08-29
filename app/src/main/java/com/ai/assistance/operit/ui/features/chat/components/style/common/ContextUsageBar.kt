package com.ai.assistance.operit.ui.features.chat.components.style.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Bară de context stil Cline: `4.9k ▓▓░░░░░░░ 128k`.
 *
 * - `total` este calculat în call-site (via ModelContextRegistry pe baza modelId),
 *   deci aici doar randăm: Used / bară / Total și un tooltip la long-press.
 * - Tooltip (long-press) — exact ca în Cline screenshot 3:
 *   Context Window X%
 *   Used: Yk
 *   Total: Zm
 *   Remaining: Wm
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextUsageBar(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val totalLong = total.toLong().coerceAtLeast(0L)
    if (totalLong <= 0L) return

    val usedLong = used.coerceAtLeast(0).toLong()
    val remaining = (totalLong - usedLong).coerceAtLeast(0L)
    val usageFraction = (usedLong.toFloat() / totalLong.toFloat()).coerceIn(0f, 1f)
    val percent = usageFraction * 100f
    val animatedProgress by animateFloatAsState(
        targetValue = usageFraction,
        animationSpec = tween(durationMillis = 300),
        label = "ContextUsageBarProgress",
    )
    val barColor = when {
        usageFraction > 0.90f -> MaterialTheme.colorScheme.error
        usageFraction > 0.75f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    var showTooltip by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .combinedClickable(
                    onClick = { showTooltip = !showTooltip },
                    onLongClick = { showTooltip = !showTooltip },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatTokenCountCompact(usedLong),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = labelColor,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }

            Text(
                text = formatTokenCountCompact(totalLong),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = labelColor,
            )
        }

        if (showTooltip) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 6.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TooltipText(text = "Context Window  ${"%.1f%%".format(Locale.US, percent)}", bold = true)
                    TooltipText(text = "Used: ${formatTokenCountCompact(usedLong)}")
                    TooltipText(text = "Total: ${formatTokenCountCompact(totalLong)}")
                    TooltipText(text = "Remaining: ${formatTokenCountCompact(remaining)}")
                }
            }
        }
    }
}

@Composable
private fun TooltipText(text: String, bold: Boolean = false) {
    Text(
        text = text,
        style = if (bold) {
            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        } else {
            MaterialTheme.typography.labelSmall
        },
        color = MaterialTheme.colorScheme.inverseOnSurface,
    )
}

/**
 * Formatare compacta stil Cline a token-urilor:
 * <1k → exact (ex. "842"); <1M → o zecimală "k" (ex. "47.4k"); ≥1M → "1.3m".
 */
internal fun formatTokenCountCompact(tokens: Long): String {
    return when {
        tokens < 1_000L -> tokens.toString()
        tokens < 1_000_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
        else -> String.format(Locale.US, "%.1fm", tokens / 1_000_000.0)
    }
}
