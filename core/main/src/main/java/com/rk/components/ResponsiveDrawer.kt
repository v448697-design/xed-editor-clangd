package com.rk.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rk.settings.Settings

@Composable
inline fun getDrawerWidth(): Dp {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    val width = with(density) { (widthPx * 0.83f).toDp() }
    return width
}

var isPermanentDrawer by mutableStateOf(false)
    private set

@Composable
fun ResponsiveDrawer(
    drawerState: DrawerState,
    fullscreen: Boolean,
    mainContent: @Composable () -> Unit,
    sheetContent: @Composable ColumnScope.() -> Unit,
) {
    if (Settings.desktop_mode) {
        val screenWidthDp = LocalWindowInfo.current.containerSize.width.dp
        isPermanentDrawer = remember(screenWidthDp) { screenWidthDp >= 1080.dp }
    }

    val shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)

    if (isPermanentDrawer) {
        PermanentNavigationDrawer(
            content = mainContent,
            modifier = Modifier.imePadding().systemBarsPadding(),
            drawerContent = {
                PermanentDrawerSheet(
                    windowInsets = if (fullscreen) WindowInsets() else DrawerDefaults.windowInsets,
                    drawerShape = shape,
                    content = sheetContent,
                )
            },
        )
    } else {
        ModalNavigationDrawer(
            modifier = Modifier.imePadding().systemBarsPadding(),
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            content = mainContent,
            drawerContent = {
                ModalDrawerSheet(
                    windowInsets = if (fullscreen) WindowInsets() else DrawerDefaults.windowInsets,
                    modifier = Modifier.width(getDrawerWidth()).clip(shape),
                    drawerShape = shape,
                    content = {
                        AnimatedVisibility(
                            visible = drawerState.currentValue != androidx.compose.material3.DrawerValue.Closed || drawerState.isOpen,
                            enter = fadeIn(tween(180)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 6 },
                            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 8 },
                        ) {
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                sheetContent()
                            }
                        }
                    },
                )
            },
        )
    }
}
