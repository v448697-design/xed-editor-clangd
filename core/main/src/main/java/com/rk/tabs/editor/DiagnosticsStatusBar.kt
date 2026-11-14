package com.rk.tabs.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.icons.Error
import com.rk.icons.XedIcons
import com.rk.resources.strings

private val WarningColor = Color(0xFFE6B34A)

/** Compact Helix-style diagnostics counters for the current editor tab. */
@Composable
fun DiagnosticsStatusBar(editorTab: EditorTab) {
    val state = editorTab.editorState
    val visible = state.diagnosticsReceived || state.isConnectingLsp

    AnimatedVisibility(visible = visible) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.isConnectingLsp) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text("LSP", style = MaterialTheme.typography.labelSmall)
                }

                if (state.diagnosticsReceived) {
                    CounterItem(
                        icon = { tint ->
                            Icon(
                                imageVector = XedIcons.Error,
                                contentDescription = stringResource(strings.error),
                                tint = tint,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        count = state.errorCount,
                        color = MaterialTheme.colorScheme.error,
                    )

                    if (state.warningCount > 0) {
                        CounterItem(
                            icon = { tint ->
                                Icon(
                                    imageVector = Icons.Outlined.Warning,
                                    contentDescription = stringResource(strings.warning),
                                    tint = tint,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            count = state.warningCount,
                            color = WarningColor,
                        )
                    }

                    if (state.infoCount > 0) {
                        CounterItem(
                            icon = { tint ->
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = stringResource(strings.info),
                                    tint = tint,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            count = state.infoCount,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CounterItem(icon: @Composable (Color) -> Unit, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        icon(color)
        Text(text = count.toString(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
