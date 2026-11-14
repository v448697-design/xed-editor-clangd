package com.rk.filetree

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.components.compose.utils.addIf
import com.rk.file.FileObject
import com.rk.resources.drawables
import com.rk.settings.Settings
import com.rk.utils.drawErrorUnderline
import com.rk.utils.getGitColor
import com.rk.utils.getUnderlineColor

@Composable
fun FileTreeNodeItem(
    modifier: Modifier,
    root: FileObject,
    node: FileTreeNode,
    depth: Int,
    displayName: String,
    onFileClick: (FileTreeNode) -> Unit,
    viewModel: FileTreeViewModel,
) {
    val isHidden = node.file.getName().startsWith(".")
    if (isHidden && !Settings.show_hidden_files_drawer) return

    val isExpanded = viewModel.isNodeExpanded(root, node.file)
    val horizontalPadding = (depth * 16).dp

    val isLoading = viewModel.isNodeLoading(node.file)
    val isCut = viewModel.isNodeCut(node.file)

    val isFileSelected = viewModel.isFileSelected(root, node.file)
    val isFileFocused = viewModel.isFileFocused(root, node.file)

    val context = LocalContext.current
    val rowColor =
        when {
            isFileSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            isFileFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            else -> Color.Transparent
        }
    val rowShape = RoundedCornerShape(8.dp)
    val guideColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    // Load children when expanded
    LaunchedEffect(node.file, isExpanded) {
        if (isExpanded && node.isDirectory) {
            viewModel.loadChildrenForNode(node)
        }
    }

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .drawBehind {
                        if (depth > 0) {
                            val step = 16.dp.toPx()
                            val stroke = 1.dp.toPx()
                            for (level in 1..depth) {
                                val x = (level * step) - (step / 2f)
                                drawLine(
                                    color = guideColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = stroke,
                                )
                            }
                        }
                    }
                    .addIf(isCut) { alpha(0.5f) }
                    .clip(rowShape)
                    .background(rowColor)
                    .combinedClickable(
                        onClick = {
                            if (viewModel.isAnyFileSelected(root)) {
                                viewModel.toggleSelection(root, node.file)
                                return@combinedClickable
                            }

                            if (node.isDirectory) {
                                viewModel.toggleNodeExpansion(root, node.file)
                                return@combinedClickable
                            }

                            onFileClick(node)
                        },
                        onLongClick = { viewModel.toggleSelection(root, node.file) },
                    )
                    .heightIn(min = 40.dp)
                    .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(horizontalPadding))

            if (node.isDirectory) {
                val rotationDegree by
                    animateFloatAsState(
                        targetValue = if (!isExpanded) 0f else 90f,
                        animationSpec = tween(120),
                        label = "rotation",
                    )

                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(9.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painter = painterResource(drawables.chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp).rotate(rotationDegree),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }

            Box(modifier = Modifier.addIf(isHidden) { alpha(0.5f) }) {
                FileNameIcon(node.name, node.isDirectory, isExpanded = isExpanded)
            }

            Spacer(modifier = Modifier.width(8.dp))

            val underlineColor = getUnderlineColor(context, viewModel, node.file)
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.weight(1f).addIf(underlineColor != null) {
                        drawErrorUnderline(underlineColor!!)
                    },
                color = getGitColor(node.file) ?: MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
