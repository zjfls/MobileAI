package com.mobileai.notes.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.oppo.OppoPenKit
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoFixOff
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolBar(
    modifier: Modifier = Modifier,
    tool: ToolKind,
    isEraser: Boolean,
    colorArgb: Long,
    size: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToolChange: (ToolKind) -> Unit,
    onEraser: () -> Unit,
    onColorChange: (Long) -> Unit,
    onSizeChange: (Float) -> Unit,
) {
    val scrollState = rememberScrollState()
    var customColorOpen by remember { mutableStateOf(false) }
    var customColorText by remember { mutableStateOf("FF111111") }
    val sizeLabel =
        if (isEraser) {
            "橡皮"
        } else {
            when (tool) {
                ToolKind.PEN -> "钢笔"
                ToolKind.PENCIL -> "铅笔"
                ToolKind.HIGHLIGHTER -> "荧光笔"
            }
        }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalIconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.Outlined.Undo, contentDescription = "撤销")
            }
            FilledTonalIconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.Outlined.Redo, contentDescription = "重做")
            }

            VerticalDivider()

            ToolIconButton(
                selected = !isEraser && tool == ToolKind.PEN,
                iconSelected = Icons.Filled.Create,
                icon = Icons.Outlined.Create,
                label = "钢笔",
                onClick = {
                    OppoPenKit.tryToolSwitchVibration(isEraser = false)
                    onToolChange(ToolKind.PEN)
                },
            )
            ToolIconButton(
                selected = !isEraser && tool == ToolKind.PENCIL,
                iconSelected = Icons.Filled.BorderColor,
                icon = Icons.Outlined.BorderColor,
                label = "铅笔",
                onClick = {
                    OppoPenKit.tryToolSwitchVibration(isEraser = false)
                    onToolChange(ToolKind.PENCIL)
                },
            )
            ToolIconButton(
                selected = !isEraser && tool == ToolKind.HIGHLIGHTER,
                iconSelected = Icons.Filled.AutoFixHigh,
                icon = Icons.Outlined.AutoFixHigh,
                label = "荧光笔",
                onClick = {
                    OppoPenKit.tryToolSwitchVibration(isEraser = false)
                    onToolChange(ToolKind.HIGHLIGHTER)
                },
            )
            ToolIconButton(
                selected = isEraser,
                iconSelected = Icons.Filled.AutoFixOff,
                icon = Icons.Outlined.AutoFixOff,
                label = "橡皮",
                onClick = {
                    OppoPenKit.tryToolSwitchVibration(isEraser = true)
                    onEraser()
                },
            )

            VerticalDivider()

            ColorDot(0xFF111111, colorArgb, onColorChange)
            ColorDot(0xFF2563EB, colorArgb, onColorChange)
            ColorDot(0xFFDC2626, colorArgb, onColorChange)
            ColorDot(0xFF16A34A, colorArgb, onColorChange)
            ColorDot(0xFF9333EA, colorArgb, onColorChange)
            ColorDot(0xFFF59E0B, colorArgb, onColorChange)
            OutlinedButton(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                onClick = { customColorOpen = true },
            ) { Text("自定义色") }

            VerticalDivider()

            Column(modifier = Modifier.width(200.dp)) {
                Slider(
                    value = size,
                    onValueChange = onSizeChange,
                    valueRange = 2f..22f,
                )
                Text(
                    text = "$sizeLabel 粗细 ${size.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

    if (customColorOpen) {
        AlertDialog(
            onDismissRequest = { customColorOpen = false },
            title = { Text("自定义颜色") },
            text = {
                OutlinedTextField(
                    value = customColorText,
                    onValueChange = { input ->
                        customColorText = input
                            .trim()
                            .removePrefix("#")
                            .uppercase()
                            .filter { it.isDigit() || it in 'A'..'F' }
                            .take(8)
                    },
                    singleLine = true,
                    label = { Text("ARGB 十六进制（例如 FF111111）") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val hex = customColorText.padStart(8, 'F').take(8)
                        runCatching {
                            val argb = hex.toLong(16)
                            onColorChange(argb)
                        }
                        customColorOpen = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { customColorOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ToolIconButton(
    selected: Boolean,
    iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledIconButton(onClick = onClick) {
            Icon(iconSelected, contentDescription = label)
        }
    } else {
        FilledTonalIconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun ColorDot(
    color: Long,
    current: Long,
    onColorChange: (Long) -> Unit,
) {
    val selected = color == current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .background(Color(color))
            .clickable { onColorChange(color) },
    )
}

@Composable
private fun VerticalDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)),
    )
    Spacer(Modifier.width(2.dp))
}
