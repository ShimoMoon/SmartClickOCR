# SmartClickOCR

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet)

一个 Android 自动化连点器，支持 **按顺序执行动作**、**OCR 文字识别后自动停止**、**悬浮球控制**，无需 Root。

> 最初为自用而写，用来处理界面上的重复性点击，以及「等到某个提示出现就自动停」这类需求。

---

## 📥 直接下载安装（推荐）

大多数人只需要 APK，**不需要安装 Android Studio、不需要会编程**。

1. 打开本仓库的 [**Releases**](../../releases) 页面
2. 下载最新的 `SmartClickOCR-x.x.apk`
3. 传到手机上点击安装
4. 首次打开按界面提示授予权限（见下方「初次使用」）

**系统要求**：Android 8.0（API 26）及以上

> ⚠️ APK 使用 debug 签名。如果你之前装过旧版本，可以直接覆盖安装；
> 如果提示「签名冲突」，先卸载旧版再装（卸载会清除已保存的方案配置）。

---

## ✨ 功能

### 动作类型

| 动作 | 说明 |
|---|---|
| **点击** | 点击指定屏幕坐标 |
| **返回** | 执行 Android 返回键 |
| **等待** | 单纯等待指定时长 |
| **滑动** | 从起点滑到终点，可设滑动时长 |
| **OCR 检测** | 识别指定区域的文字，完全匹配则停止任务 |
| **条件停止** | 与 OCR 检测相同，语义更直白：「出现『任务完成』就停止」 |

### 核心特性

- **可视化选点**：拖动准星选点位，拖动矩形框选 OCR 区域，**不用手输坐标**
- **手动输入坐标**：也可以直接填数字，方便把配置搬到另一台手机复用
- **测试功能**：测试点击（真实派发+红圈反馈）、测试 OCR（显示识别文本+匹配结果）
- **悬浮球控制**：切到目标 App 后，用悬浮球开始 / 暂停 / 继续 / 停止
- **精确匹配**：OCR 文字严格比对，`任务完成` 不会误匹配 `任务已完成`
- **多方案管理**：可建多个方案，自动保存，重启不丢
- **完全离线**：OCR 模型打包在 APK 内，不联网、不上传截图

---

## 🚀 初次使用

打开 App 后，权限状态卡片里有四项，点「去授权」逐项开启：

| 权限 | 作用 | 必要性 |
|---|---|---|
| **无障碍服务** | 派发点击/滑动/返回手势 | **必须**，否则无法执行任何动作 |
| **悬浮窗** | 在其他 App 上方显示悬浮球、准星、框选框 | **强烈建议** |
| **屏幕截图** | 只有 OCR 动作需要，用于读取屏幕文字 | 用 OCR 时才需要 |
| **电池优化** | 防止系统在后台杀掉任务 | 建议 |

### 无障碍服务怎么开

设置 → 无障碍 → 已安装的服务 → **SmartClickOCR** → 开启

部分 Android 版本会提示「此操作可能搜集你的数据」，这是系统的统一警告文案。
本应用不会读取或上传任何界面内容，源码可自行审查。

### 快速上手

1. 主界面 →「新增动作」→ 选一个类型
2. 选「点击」→ 点「**选择位置（准星）**」
   - App 会退到后台，屏幕上出现可拖动的准星
   - 把准星拖到目标按钮中心
   - 点「**确认位置**」（**这一步不会点击目标 App，绝不会误触**）
3. 回到编辑页，可以点「**测试点击**」验证位置
4. 设置「执行前延迟」（每个动作都会先等这么久）
5. 回主界面 →「**开始**」→ 任务进入「待命」状态
6. 切到目标 App → 点悬浮球 →「**开始**」，任务才真正运行

> 💡 为什么要多点一步「待命」？
> 如果点开始就直接跑，前几个动作会打在 SmartClickOCR 自己的界面上。
> 现在必须你在目标 App 里手动触发，保证不会点错地方。

---

## 🔤 OCR 使用要点

OCR 是最容易踩坑的部分，这几条很关键：

1. **区域不要框太紧**。只框住单个字时识别引擎容易判错；
   把**目标字所在的整行**（或周围一小圈）框进来，准确率高得多。
2. **匹配是完全一致的**。识别结果去掉首尾空格后逐字比对。
   如果测试显示「❌ 不匹配」，把「最近识别」里的文字照着改到目标文字里。
3. **一定要点「测试 OCR」** 先验证，再开始任务。
4. 截图受保护的界面（银行、支付类 App 常设 `FLAG_SECURE`）会读取失败，
   这是系统限制，无法绕过。

---

## 🔨 从源码构建

### 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 37 / buildTools 36.0.0 |
| Gradle | 8.14.3（wrapper 已包含） |
| AGP | 8.13.0 |

### 步骤

```bash
git clone https://github.com/Shimo/SmartClickOCR.git
cd SmartClickOCR
```

创建 `local.properties`，写入你的 SDK 路径：

```properties
# Windows
sdk.dir=D\:\\Android\\Sdk
# macOS / Linux
# sdk.dir=/Users/you/Library/Android/sdk
```

构建：

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

也可以直接用 **Android Studio** 打开项目根目录。

### 运行测试

```bash
./gradlew testDebugUnitTest   # 73 个单元测试
```

---

## 📁 项目结构

```
app/src/main/java/io/github/shimo/smartclickocr/
├─ model/      数据模型（动作类型、执行结果、引擎状态）
├─ engine/     执行引擎（串行调度、循环、暂停/继续/停止）
├─ service/   前台服务 + 无障碍服务（手势派发）
├─ ocr/       截图（MediaProjection）+ ML Kit 识别 + 匹配规则
├─ overlay/   悬浮球、准星选点、OCR 框选、点击反馈
├─ ui/        主界面、动作编辑页
├─ data/      方案持久化（版本化 JSON）
└─ util/      坐标换算、屏幕信息、运行日志
```

### 关键设计说明

- **坐标一致性**：选点、测试点击、正式点击、OCR 裁剪全部走 `CoordinateMapper`，
  确保「预览位置」和「实际位置」不会偏移
- **截图偏移处理**：MediaProjection 返回的画面可能比屏幕略矮（系统栏差异），
  裁剪时会先扣除这个差值再换算，否则越靠下的区域偏得越多
- **逐块匹配**：OCR 会把相邻文字合并成一段，所以除了整体比对，
  还会逐个文本块比对，避免「目标字明明识别到了却不算匹配」
- **串行保证**：引擎等前一个动作的回调返回后才执行下一个，不会重叠

---

## ⚠️ 已知限制

- **仅在本机模拟器验证过部分功能**，真机的 MediaProjection 截图行为可能存在差异
- **ColorOS / MIUI 等国产 ROM** 需要额外允许「自启动」「后台运行」，否则容易被杀
- **部分设备可能存在 OCR 框选纵向偏移**：由于不同 ROM 对 MediaProjection 截图坐标与系统栏的处理不同，实际识别区域可能比框选区域略高或略低。请先使用“测试 OCR”确认结果；如有偏移，请将框选区域向相反方向微调后再运行任务。
  
  **Some devices may have a vertical OCR selection offset**: ROMs can handle MediaProjection coordinates and system bars differently, so the actual recognition area may sit slightly above or below the selected box. Use **Test OCR** first; if needed, move the selection box slightly in the opposite direction before running the task.

- **不支持绕过屏幕保护**：受保护界面无法截图
- **需要手动授权**：无障碍服务必须用户在系统设置中开启，应用不会也无法静默开启

---

## 🤝 参与贡献

欢迎提 Issue 和 PR。

提交前请确保：
- `./gradlew testDebugUnitTest` 全部通过
- 代码注释说明「为什么」而非「是什么」
- 涉及 OCR / 坐标的改动请附上真机测试截图

---

## 📄 许可证

[MIT](LICENSE) © 2026 Shimo

使用的第三方库：

- [ML Kit Text Recognition (Chinese)](https://developers.google.com/ml-kit/vision/text-recognition/v2) — Google，随 APK 打包的离线模型
- [AndroidX](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Apache-2.0

---

## ⚖️ 免责声明

本项目仅供**个人学习与自动化效率**使用。

使用者需自行确保其使用行为符合目标应用的服务条款与当地法律法规。
作者不对因使用本工具导致的账号封禁、数据丢失或其他损失承担责任。

请勿用于任何未经授权的批量操作、抢购、刷量等用途。
