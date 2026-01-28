# MobileAI Notes（OPPO Pad 手写笔记 / PDF 批注）

一个面向 **最新 OPPO Pad** 的手写笔记应用骨架，技术栈以“官方/标准”为主：

- UI：Jetpack Compose + Material 3
- 笔迹：Jetpack Ink（`androidx.ink`）
- PDF 阅读：`android.graphics.pdf.PdfRenderer`
- OPPO Pencil 增强（可选）：OPPO 开放平台「手写笔服务」`ipe_sdk`（鉴权/特性/震动反馈/预测）

## 运行环境

- Android Studio
- JDK 17（本机若提示 “Unable to locate a Java Runtime”，请安装 Temurin 17 或 Android Studio 自带 JDK）

## 运行

1. 用 Android Studio 打开本仓库
2. 选择 `app` 运行

## 命令行构建（推荐）

本仓库提供构建脚本，默认规则是：

- **优先使用 Android Studio 自带的 JBR/JDK**（如果系统安装了 Android Studio）
- 如果没有 Android Studio，再使用系统配置的 `JAVA_HOME` / `java`

常用命令：

- Debug APK：`scripts/build-debug`
- 任意 Gradle 任务：`scripts/gradle <tasks...>`

## OPPO Pencil（可选增强）

本项目默认 **不强依赖** OPPO 的 `ipe_sdk`（避免没有授权码/依赖时无法编译或运行）。如果你要在 OPPO Pad 上启用专属能力：

1. 打开 OPPO 开放平台「手写笔服务」文档：`https://open.oppomobile.com/documentation/page/info?id=13306`
2. 申请“手写笔服务”权限并获取授权码（文档里有入口）
3. 按文档集成 `ipe_sdk`（AAR 或 Maven）并在 `AndroidManifest.xml` 配置：
   - `meta-data`：`com.oplus.ocs.ipepencil.AUTH_CODE`
4. 代码层：`app/src/main/java/com/mobileai/notes/oppo/OppoPenKit.kt` 会通过反射尝试启用：
   - 连接状态/支持特性查询
   - 功能震动/书写反馈震动（设备支持时）

> 注意：授权码/签名与包名强绑定，请按你的应用包名与签名申请。
