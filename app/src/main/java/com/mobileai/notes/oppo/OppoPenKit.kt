package com.mobileai.notes.oppo

import android.content.Context
import android.util.Log

/**
 * OPPO 开放平台 ipe_sdk 的“可选增强”接入点。
 *
 * - 本文件不直接依赖 ipe_sdk（避免没有 SDK/授权码时编译失败）
 * - 若你的工程已按开放平台文档集成 ipe_sdk，本类会在运行时通过反射启用部分能力
 */
object OppoPenKit {
    private const val TAG = "OppoPenKit"

    @Volatile
    private var initialized = false

    fun tryInit(context: Context) {
        if (initialized) return
        initialized = true

        runCatching {
            val unitClass = Class.forName("com.oplus.ocs.ipemanager.sdk.PencilManagerUnit")
            val getClient = unitClass.getMethod("getPencilManagerClient", Context::class.java)
            getClient.invoke(null, context.applicationContext)
        }.onFailure {
            Log.d(TAG, "ipe_sdk not available: ${it.javaClass.simpleName}")
        }
    }

    fun tryToolSwitchVibration(isEraser: Boolean) {
        runCatching {
            val pmClass = Class.forName("com.oplus.ocs.ipemanager.sdk.PencilManager")
            val getInstance = pmClass.getMethod("getInstance")
            val pencilManager = getInstance.invoke(null) ?: return

            val vibClass = Class.forName("com.oplus.ocs.ipemanager.sdk.Vibration")
            val enumConstants = vibClass.enumConstants ?: return
            val targetName = if (isEraser) "ERASER" else "FUNCTION_VIBRATION"
            val vibration = enumConstants.firstOrNull { (it as Enum<*>).name == targetName } ?: return

            // Prefer setVibrationType + startVibration (both exist in ipe_sdk demo/APIs).
            runCatching {
                pmClass.getMethod("setVibrationType", vibClass).invoke(pencilManager, vibration)
            }
            runCatching {
                pmClass.getMethod("startVibration", vibClass).invoke(pencilManager, vibration)
            }
        }.onFailure {
            // Quietly ignore on non-OPPO / missing permissions / no auth.
        }
    }

    fun tryGetStatusText(): String? {
        return runCatching {
            val pmClass = Class.forName("com.oplus.ocs.ipemanager.sdk.PencilManager")
            val getInstance = pmClass.getMethod("getInstance")
            val pencilManager = getInstance.invoke(null) ?: return null

            val getConnect = pmClass.getMethod("getPencilConnectState")
            val connectState = getConnect.invoke(pencilManager)

            val getFeatures = pmClass.getMethod("getSupportFeatures")
            val features = getFeatures.invoke(pencilManager)

            val getSdkVersion = runCatching { pmClass.getMethod("getSupportSdkVersion") }.getOrNull()
            val sdkVersion = getSdkVersion?.invoke(pencilManager)

            buildString {
                appendLine("connectState: $connectState")
                appendLine("supportFeatures: $features")
                if (sdkVersion != null) appendLine("supportSdkVersion: $sdkVersion")
            }.trim()
        }.getOrNull()
    }
}
