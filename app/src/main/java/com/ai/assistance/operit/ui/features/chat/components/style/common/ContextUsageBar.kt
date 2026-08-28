package com.ai.assistance.operit.ui.features.chat.components.style.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Cline 风格的上下文用量条：`47.4k ▓▓▓░░░░░░░ 1.3m`
 *
 * 修改意图：在输入卡片顶部提供一眼可读的上下文占用可视化，
 * 与 ChatScreenHeader 中已有的圆形进度指示器共用同一套颜色阈值
 * （>75% tertiary，>90% error），保证全局视觉语义一致。
 *
 * @param currentTokens 当前上下文 token 数（调用方传入 projectedTokens =
 *   当前窗口 + 输入草稿预估，草稿为空时即等于当前窗口大小）
 * @param maxTokens 模型上下文上限（maxWindowSizeInK * 1024）
 */
@Composable
fun ContextUsageBar(
    currentTokens: Long,
    maxTokens: Long,
    modifier: Modifier = Modifier,
) {
    // 尚未配置上下文长度（maxTokens == 0）时不渲染：无数据可展示，并非降级逻辑
    if (maxTokens <= 0L) return

    val usageFraction = (currentTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatTokenCountCompact(currentTokens),
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
            text = formatTokenCountCompact(maxTokens),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = labelColor,
        )
    }
}

/**
 * Cline 风格的紧凑 token 格式化：
 * <1 000 → 原样（如 "842"）；<1M → 一位小数 "k"（如 "47.4k"）；≥1M → "1.3m"
 */
internal fun formatTokenCountCompact(tokens: Long): String {
    return when {
        tokens < 1_000L -> tokens.toString()
        tokens < 1_000_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
        else -> String.format(Locale.US, "%.1fm", tokens / 1_000_000.0)
    }
}
