package com.mobileai.notes.ui.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolBar(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = onUndo, enabled = canUndo) { Text("撤销") }
        Button(onClick = onRedo, enabled = canRedo) { Text("重做") }

        ToolButton(
            label = "钢笔",
            selected = !isEraser && tool == ToolKind.PEN,
            onClick = {
                OppoPenKit.tryToolSwitchVibration(isEraser = false)
                onToolChange(ToolKind.PEN)
            },
        )
        ToolButton(
            label = "铅笔",
            selected = !isEraser && tool == ToolKind.PENCIL,
            onClick = {
                OppoPenKit.tryToolSwitchVibration(isEraser = false)
                onToolChange(ToolKind.PENCIL)
            },
        )
        ToolButton(
            label = "荧光笔",
            selected = !isEraser && tool == ToolKind.HIGHLIGHTER,
            onClick = {
                OppoPenKit.tryToolSwitchVibration(isEraser = false)
                onToolChange(ToolKind.HIGHLIGHTER)
            },
        )
        ToolButton(
            label = "橡皮",
            selected = isEraser,
            onClick = {
                OppoPenKit.tryToolSwitchVibration(isEraser = true)
                onEraser()
            },
        )

        Spacer(Modifier.width(6.dp))

        ColorButton(0xFF111111, colorArgb, onColorChange)
        ColorButton(0xFF1976D2, colorArgb, onColorChange)
        ColorButton(0xFFD32F2F, colorArgb, onColorChange)
        ColorButton(0xFF388E3C, colorArgb, onColorChange)
        ColorButton(0xFFFFC107, colorArgb, onColorChange)
        Button(onClick = { customColorOpen = true }) { Text("自定义色") }

        Spacer(Modifier.width(8.dp))

        Slider(
            value = size,
            onValueChange = onSizeChange,
            valueRange = 2f..22f,
            modifier = Modifier.width(180.dp),
        )
        Text("粗细: ${size.toInt()}")
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
private fun ToolButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.buttonColors(containerColor = Color(0xFF111111), contentColor = Color.White)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(label)
    }
}

@Composable
private fun ColorButton(
    color: Long,
    current: Long,
    onColorChange: (Long) -> Unit,
) {
    val selected = color == current
    Button(
        onClick = { onColorChange(color) },
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(color),
            contentColor = if (selected) Color.White else Color.Transparent,
        ),
    ) {
        Text(if (selected) "✓" else " ")
    }
}
