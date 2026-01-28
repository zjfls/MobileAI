# 需求线（一次性完整版）

> 目标：在 **最新 OPPO Pad** 上做一款“写字/做题为主”的手写笔记 App，覆盖 **空白画布笔记** 与 **PDF 阅读批注** 两大场景；优先使用 Android/Jetpack 官方能力，在可获取授权的前提下再启用 OPPO Pencil 专属 SDK 增强。

## 0. 范围与版本规划（交付口径）

### 0.1 V1（本仓库目标）

- 空白画布：多页、模板背景、笔/橡皮/撤销重做、导出 PDF/PNG
- PDF：导入/阅读（滚动 + 适配宽度）、逐页批注、导出“带批注 PDF”
- 关键体验：低延迟书写、基础掌托（palm rejection）、局部擦除
- OPPO Pencil：基于 **标准 Android 触控笔 API** 直接可用；若集成 `ipe_sdk` 则提供连接/特性查询与震动反馈（可选）

### 0.2 VNext（后续增强，不阻塞 V1）

- PDF/画布：双指缩放/平移（需要做坐标变换与输入映射）
- 页面缩略图/页管理（快速跳转、拖拽排序）
- 更多笔刷与笔尖（纹理、速度敏感、真实铅笔颗粒等）
- 多设备同步（WebDAV/私有云/本地导出导入）

## 1. 产品定位

在 **最新 OPPO Pad** 上提供“写字为主、做题为主”的手写笔记应用，覆盖两类核心场景：

1) **空白画布笔记**：像纸一样写、擦、翻页、导出。  
2) **PDF 阅读 + 批注**：阅读教材/试卷 PDF，并进行手写批注与局部擦除。

技术约束：

- UI 必须使用 **Jetpack Compose**
- 笔迹引擎使用 **官方 Jetpack Ink（androidx.ink）**
- OPPO Pencil 专属能力通过 **OPPO 开放平台 ipe_sdk**（可选增强，不强绑；无 SDK/无授权也必须可编译运行）

## 2. 目标设备与输入能力（官方/标准优先）

### 2.1 目标设备

- 目标：最新 OPPO Pad（Android 12+）
- 触控笔：OPPO Pencil（支持压感/倾斜/悬停等以系统上报为准）

### 2.2 Android 官方输入 API（必做）

- 事件：`MotionEvent`（`ACTION_DOWN/MOVE/UP`、`ACTION_HOVER_MOVE`）
- 工具类型：`TOOL_TYPE_STYLUS`、`TOOL_TYPE_ERASER`（笔/橡皮头）
- 轴数据（按设备上报可用性读取）：
  - 压感：`AXIS_PRESSURE`
  - 倾角：`AXIS_TILT`
  - 方向：`AXIS_ORIENTATION`
  - 距离/悬停：`AXIS_DISTANCE`
- 按键：`BUTTON_STYLUS_PRIMARY/SECONDARY`（若硬件有侧键）
- 低延迟：`requestUnbufferedDispatch(MotionEvent)`（尽可能减少系统缓冲）

## 3. 用户故事（核心）

- 作为用户，我可以导入一个 PDF 并在每一页上用笔写字、画线、做标注。
- 作为用户，我可以在空白笔记里写字做题，支持多页。
- 作为用户，我可以像真实橡皮擦一样 **局部擦除**（擦掉笔画的一部分，而不是整笔删除）。
- 作为用户，我可以撤销/重做，避免误操作丢内容。
- 作为用户，我希望书写“跟手、低延迟、不掉帧”，并尽可能利用 OPPO Pencil 的能力（压感/倾斜/震动反馈/预测）。

## 4. 功能需求（FR）

### 4.1 首页/文件管理

- 新建：空白笔记（默认 1 页）
- 导入：PDF（SAF 选取文档）
- 新建：AI 试卷（题目图片页 + 手写作答）
- 最近：最近打开列表（包含空白笔记与 PDF）
- 重命名/删除

### 4.2 编辑器通用能力

- 工具栏（底部）：
  - 笔：钢笔（`StockBrushes.pressurePen()`）、铅笔（`StockBrushes.marker()`）、荧光笔（`StockBrushes.highlighter()`）
  - 橡皮擦：**局部擦除**
  - 颜色（预设色 + 自定义）
  - 粗细（滑条）
  - 撤销 / 重做
- 输入策略：
  - 默认优先使用触控笔输入（stylus）；手指用于滚动/缩放（可配置）
  - 支持悬停（hover）时显示笔尖预览（若设备上报）
  - 掌托（palm rejection）：触控笔落笔期间，屏蔽手指触摸对画布的误触
- 交互：
  - 笔写：落笔即出墨
  - 橡皮：按半径擦除，擦到就“挖掉”那一段笔迹，并将原笔画分裂成多段可撤销

### 4.3 空白画布笔记

- 多页：新增/删除页；缩略图快速跳转（可先用列表替代）
- 模板：空白/横线/方格/点阵/DOTs/Cornell（渲染背景）
- 导出：
  - 导出 PDF（将每页渲染为图片并生成 PDF；保留矢量非必需）
  - 导出 PNG（单页）

### 4.4 PDF 阅读与批注

- 阅读：
  - PDF 渲染（逐页显示、滚动阅读）
  - 支持缩放（至少“适配宽度”与手势缩放其一；V1 先适配宽度，VNext 增加手势缩放/平移）
- 批注：
  - 每页独立保存笔迹
  - 批注覆盖在 PDF 上方（不破坏原 PDF）
  - 支持导出“带批注的 PDF”（可通过重新渲染合成实现）

### 4.5 AI 试卷 / 做题（亮点）

目标：把“题目图片 + 手写作答 + AI 解答 + 同步”整合成一条闭环。

- Host 连接：
  - 配置 `baseUrl`（例如 `https://api.mock-edu.com`）
  - 支持保存/复用
- 拉题成卷：
  - `GET {baseUrl}/questions` 获取题目列表（支持 `imageUrl` 或 `imageBase64`）
  - 每道题生成一页：题目图片做页面底图，Ink 画布覆盖其上
- AI 出题成卷：
  - `POST {baseUrl}/papers/generate`（prompt + count）返回题目列表（同上）
- AI 解答：
  - `POST {baseUrl}/ai/solve`（上传该页合成 PNG）返回解答文本
- 同步到 Host：
  - `POST {baseUrl}/pages/upload`（上传该页合成 PNG + pageIndex + paperId）

### 4.6 OPPO Pencil 增强（可选）

- 鉴权与连接状态展示（可在调试页）
- 震动反馈：
  - 工具切换（例如橡皮/笔）
  - 书写反馈（设备支持时）
- 预测：
  - 优先使用 AndroidX `input-motionprediction`
  - 若集成 OPPO 预测（`com.oplus.forecast.MotionPredictor`）可用于对比或叠加

## 5. OPPO Pencil / ipe_sdk 调研结论（写进需求口径）

### 5.1 OPPO SDK 用什么语言？

- `ipe_sdk` 以 **Android 侧 SDK（AAR / Maven 依赖）** 形式提供，面向 **Java/Kotlin** 调用。
- 交付策略：项目默认不直接依赖 `ipe_sdk`，通过“可选模块/反射”方式保证 **无授权也能编译运行**；拿到授权后再启用增强能力。

### 5.2 ipe_sdk 可能提供的能力（以实际文档/版本为准）

- 连接/状态：触控笔连接状态、支持特性、SDK 版本等
- 震动：功能震动（工具切换、书写反馈等，设备支持时）
- 预测：厂商侧 Motion Predictor（可选，用于对比/叠加）

> 说明：即使不接入 `ipe_sdk`，书写数据（坐标/压感/倾斜等）依然可以通过 Android 标准 `MotionEvent` 获取并用于渲染笔迹。

## 6. 非功能需求（NFR）

- 性能：
  - 连续书写稳定 60fps（目标 120Hz 设备尽量跟随）
  - 大 PDF（>200 页）滚动不 OOM：按需渲染、分页缓存、回收位图
- 数据可靠性：
  - 自动保存（离开页面/切后台/崩溃恢复）
  - 文件格式版本化（后续升级可迁移）
- 兼容：
  - Android 12+ 为主（最新 OPPO Pad）
  - stylus/button/hover 输入按系统上报能力自适应
- 安全：
  - SAF Uri 持久化权限管理（takePersistableUriPermission）

## 7. 数据模型（建议）

- Document
  - `BlankNotebook`：pages[]，每页 strokes[]
  - `PdfNote`：pdfUri，pageCount，perPageStrokes[pageIndex][]
  - `WorksheetNote`：pages[]（每页可带题目图片作为底图），template
- Stroke（持久化存储）
  - tool（pen/pencil/highlighter）
  - colorArgb、size
  - points[]：x、y、t、pressure、tilt、orientation（可选）

## 8. 验收标准（关键）

- 压感/倾斜：OPPO Pencil 输入下笔迹粗细/笔锋随压感/倾斜变化可见
- 局部擦除：同一笔画可被擦出缺口并分裂为多段；撤销可恢复
- PDF 批注：每页批注与 PDF 对齐，翻页不串页
- 崩溃恢复：强杀后重新打开仍能看到最近内容（至少最后一次自动保存）

## 9. 不做清单（V1 明确不做）

- 账号体系/云同步/多人协作
- OCR/手写识别/AI 题目解析
- 矢量级“重排版”导出（V1 允许用位图合成 PDF）
