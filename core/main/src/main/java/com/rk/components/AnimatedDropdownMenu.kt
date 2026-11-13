package com.rk.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Dropdown menu that reveals itself from the right edge to the left, with a soft bottom fade so the end of the menu is
 * visually clear.
 */
@Composable
fun AnimatedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing))
        }
    }

    val reveal by progress.asState()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier =
            modifier.graphicsLayer {
                scaleX = 0.74f + (0.26f * reveal)
                alpha = reveal
                transformOrigin = TransformOrigin(1f, 0f)
            },
        offset = offset,
        shape = RoundedCornerShape(12.dp),
    ) {
        content()
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(7.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                        )
                    )
        )
    }
}
