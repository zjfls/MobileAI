package com.mobileai.notes.ink

import androidx.compose.runtime.Immutable

@Immutable
data class InkFeel(
    /**
     * 粗细倍率：用于“同一个 size 滑条”下整体调粗/调细。
     * 默认 1 不改变历史笔迹。
     */
    val sizeScale: Float = 1.0f,
    /**
     * 0 = 完全跟手（不做防抖），1 = 更稳（更强的坐标平滑，可能略有拖尾）。
     */
    val stabilization: Float = 0.18f,
    /**
     * 压感曲线：1 = 原始压感；<1 让轻按更“显压”；>1 让轻按更“细”。
     */
    val pressureGamma: Float = 0.9f,
    /**
     * 增强掌托：在笔/橡皮事件附近的一小段时间里，额外吞掉手指触摸，减少误滚动/误触发。
     */
    val enhancedPalmRejection: Boolean = true,
    /**
     * 增强掌托时间窗口（毫秒）。
     */
    val palmRejectionWindowMs: Long = 280L,
)
