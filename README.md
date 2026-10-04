# Dollhouse

> 一个住在你手机屏幕上的小鲸鱼娘。

Dollhouse 是一款 Android 桌面宠物应用。安装后，鲸鱼娘「小肥鱼」会以系统悬浮窗的形式常驻在你的桌面上 —— 她会眨眼、会待机摆动、会拖到屏幕边缘探出半个身子偷看你，也会在你点她的时候开口说话，甚至能陪你聊天。

她不是一个贴图挂件，而是一个有记忆、有情绪的桌面伙伴。

---

## 目录

- [她长什么样](#她长什么样)
- [核心功能](#核心功能)
- [AI 对话与记忆](#ai-对话与记忆)
- [性能设计](#性能设计)
- [截图](#截图)
- [安装](#安装)
- [构建](#构建)
- [项目结构](#项目结构)
- [权限说明](#权限说明)
- [兼容性](#兼容性)
- [隐私](#隐私)
- [致谢与第三方组件](#致谢与第三方组件)
- [开源协议](#开源协议)

---

## 她长什么样

- **待机**：站在桌面上轻轻晃动，偶尔眨眼，长时间没人理她会自己打哈欠
- **被拖动**：身体会跟着拖拽方向倾斜，松开手自动吸附到最近的屏幕边缘
- **贴边偷看**：吸附到屏幕左右边缘后，她会侧身探出去，只留一条尾巴和半个身子在屏内，像在偷看你
- **被点击**：头顶弹出气泡说一句台词
- **三击**：召唤一个只有输入框和发送键的迷你聊天框
- **被摸**：好感度变化，影响她的表情与台词

---

## 核心功能

### 桌面悬浮人偶

基于 `TYPE_APPLICATION_OVERLAY` 系统悬浮窗实现，独立于人偶所在的 App 界面 —— 你可以回到桌面、打开别的应用，她依然待在那里。

- 任意位置自由拖动
- 松手按吸附区自动贴边
- 位置以**比例**（占屏宽/屏高的百分比）持久化，因此横竖屏切换、折叠屏展开、分屏后，她都会落在屏内正确位置
- 屏幕尺寸变化有**帧内自检兜底**：即使 ROM 不派发配置变化回调（部分 ColorOS 机型如此），帧循环也能在一秒内发现并自愈

### 多档帧率与低功耗

人偶动画不是一成不变地满帧跑：

| 状态 | 帧间隔 | 说明 |
| --- | --- | --- |
| 交互中（拖拽 / 气泡展开） | 16 ms | 满帧，保证跟手 |
| 贴边偷看且无动作 | 250 ms | 约 4 fps，只做必要重绘 |
| 不可见 / 熄屏 | 250 ms | 停止推进动画 |
| 熄屏低功耗心跳 | 1000 ms | 只跑自检，不绘制 |

熄屏时进入**心跳模式**而非彻底停帧 —— 因为广播一旦丢失就会永远醒不过来；心跳每秒自检一次「屏幕是否已亮」，亮屏立即恢复动画。

### 交互与聊天

- 单击说话、三击召唤迷你输入框
- 气泡正文按标点自动分页，长文本可展开
- 迷你聊天框与全屏聊天窗共用同一套会话与历史
- 全屏聊天窗支持自定义背景图与透明度

### 个性化

- **主题**：跟随系统深色模式，或手动指定浅色/深色；绝不跟随时间
- **莫奈取色**：从壁纸提取主色调并派生整套配色
- **人偶比例**：可调大小
- **贴边行为**：可切换左/右侧偏好

### 高级权限（Shizuku）

> **这是可选能力，不授权也能正常使用全部桌面宠物与聊天功能。**

设置页「权限」卡片下有「Shizuku 授权」一行。授权后，本应用与内置 AI 可获得 **adb shell 级**的系统能力（等同 `adb shell`，uid 2000），例如查询系统状态、读取系统设置。

- **不内置任何提权手段**：Shizuku 需要用户自行安装，并通过 `adb` 或 root 启动其服务；本应用只请求授权，不参与提权
- **授权可随时撤回**：在 Shizuku 管理器里取消勾选即失效
- **老版本兼容**：同时支持固定包名的旧版 Shizuku 与「随机包名 / 隐身」模式的新版；服务版本低于 v11 时会引导你去管理器里手动勾选授权
- **AI 侧调用需先授权**：未授权时 `shell` 工具不会出现在模型可见的工具列表里

---

## AI 对话与记忆

Dollhouse 不绑定任何一家模型服务。她在设置页提供「配置 API」入口，兼容**任意 OpenAI 格式接口**（自定义 Base URL / API Key / 模型名），并支持**保存多套配置**随时切换。每套配置各自维护自己的模型清单与**星标收藏**，互不串台。

> 本项目**不包含任何 API Key**。所有凭据由使用者在 App 内自行填写，仅保存在本机 SharedPreferences 中。

### 能力

- **多轮会话**：会话列表、重命名、历史持久化
- **记忆系统**：会话内容自动摘要与归并，避免上下文无限膨胀；可手动触发整理
- **工具调用**：支持函数调用，可接入联网搜索；授权 Shizuku 后额外开放**系统命令**（`shell`）工具，详见[高级权限（Shizuku）](#高级权限shizuku)
- **思考等级**：可调节推理强度
- **Token 统计**：按**每日 / 每周 / 累计**三个维度分段查看消耗，带迷你折线图
- **统计口径可视化**：用环形进度显示当前聊天条数占记忆触发阈值的比例

### 人设

人偶有独立的系统提示词（`SYSTEM_PROMPT`），定义了「小肥鱼」的性格、口癖与说话方式。这部分是原样保留内容，不随版本调整。

---

## 性能设计

项目在性能上做了针对性处理，桌面常驻类应用最怕的就是「安静地耗电」：

- **消除每帧对象分配**：绘制路径上的 `RectF` / `int[]` / 顶点数组全部预分配复用，避免 GC 抖动
- **顶点数据量化缓存**：网格顶点按状态量化后缓存，不重复计算
- **离屏层**：人偶视图启用硬件层
- **贴边降频**：用户离开人偶视线时（贴边态）主动降到 4 fps
- **熄屏静默**：熄屏时几乎不耗 CPU，但保证亮屏必可见
- **前台服务**：以 `specialUse` 类型前台服务保活，通知栏常驻（可从通知直接关闭人偶）
- **开机恢复**：支持开机自启（可在设置中关闭）

在一台 ColorOS 14 / 天玑 700 级别机型上采样：贴边空闲态 CPU 占用约 **3.5%**。该数值随机型、ROM 与系统负载不同而有所差异，仅供参考。

---

## 截图

> 待补充。运行 `./gradlew assembleRelease` 后安装即可直接体验。

---

## 安装

1. 前往本仓库的 **Releases** 页面下载最新 APK
2. 在手机上允许「安装未知来源应用」
3. 安装完成后打开 App，按引导授予**悬浮窗权限**
4. 回到桌面，点击「启动人偶」

首次启动会请求通知权限（用于前台服务通知），以及可选的电池优化白名单。

---

## 构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 35）
- Gradle 8.x（仓库自带 wrapper）

### 步骤

```bash
git clone git@github.com:Farewell-coder/Dollhouse.git
cd Dollhouse

# 创建签名配置（该文件已被 .gitignore 排除，不会入库）
cat > keystore.properties <<'EOF'
storeFile=/absolute/path/to/your.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
EOF

# 出包
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

> 若没有 `keystore.properties`，release 构建不会挂签名配置，但 `./gradlew assembleDebug` 依然可以正常出包用于调试。

### 版本信息

| 项 | 值 |
| --- | --- |
| applicationId | `com.dollhouse.app` |
| versionName | `0.0.2` |
| versionCode | 2 |
| compileSdk | 35 |
| minSdk | 24 |
| targetSdk | 34 |
| Java | 17 |
| AGP | 8.5.2 |

---

## 项目结构

```
Dollhouse/
├── app/
│   ├── build.gradle              # 模块配置（版本号 / 签名读取）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dollhouse/app/
│       │   ├── MainActivity.java         # 首页：启动/设置入口
│       │   ├── PetService.java           # 前台服务：悬浮窗生命周期宿主
│       │   ├── PetWindowController.java  # 窗口几何：拖动 / 贴边 / 复位
│       │   ├── PetView.java              # 人偶绘制与帧循环
│       │   ├── PetAnimator.java          # 动画状态推进
│       │   ├── PetBubble.java            # 头顶气泡
│       │   ├── ChatWindow.java           # 全屏聊天悬浮窗
│       │   ├── ChatActivity.java         # 聊天页
│       │   ├── PetTalk.java              # 对话状态机
│       │   ├── MemStore.java             # 记忆存储与摘要
│       │   ├── TokenStat.java            # Token 统计
│       │   ├── UiKit.java                # 主题与配色常量
│       │   ├── SettingsPage.java         # 设置页框架
│       │   ├── SettingsRegistry.java     # 设置项注册表
│       │   ├── HomeUi.java               # 首页 UI
│       │   ├── ApiConfigPage.java        # 配置 API 详情页（多套配置 / 模型收藏）
│       │   ├── SettingsProfiles.java     # 多套配置的存储与读写
│       │   ├── SettingsProfilePanel.java # 配置切换面板
│       │   ├── SettingsCard.java         # 设置卡片组件
│       │   ├── ChatToolRunner.java       # 模型工具调用调度
│       │   ├── PetLinkActivity.java      # 快捷方式入口页
│       │   ├── PetTileService.java       # 快捷设置磁贴
│       │   ├── PetToggle.java            # 人偶显示开关
│       │   ├── ShizukuBridge.java        # Shizuku 授权与 shell 执行（可选能力）
│       │   ├── ShellTool.java            # AI 可调用的 shell 工具
│       │   └── ...
│       └── res/
│           └── drawable/ic_tile_pet.xml  # 快捷设置磁贴图标
├── build.gradle
├── settings.gradle
├── gradle.properties
├── keystore.properties.example   # 签名配置模板
├── LICENSE                       # GPL-3.0
└── README.md
```

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 绘制桌面悬浮人偶（**核心权限**） |
| `FOREGROUND_SERVICE` | 前台服务保活，避免人偶被系统回收 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 要求声明前台服务子类型 |
| `POST_NOTIFICATIONS` | 前台服务通知（可从通知关闭人偶） |
| `INTERNET` | AI 对话请求 |
| `ACCESS_NETWORK_STATE` | 网络状态判断 |
| `VIBRATE` | 触摸反馈 |
| `RECEIVE_BOOT_COMPLETED` | 开机自动恢复人偶 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求加入省电白名单（可选，用于提升后台存活率） |
| `moe.shizuku.manager.permission.API_V23` | **可选**：Shizuku 授权后由 server 授予，用于执行 `shell` 命令 |

**`<queries>` 声明**：为了让 Android 11+ 的包可见性限制下能识别 Shizuku 管理器，Manifest 中声明了 `moe.shizuku.privileged.api`、`rikka.sui` 两个包名，以及 `REQUEST_PERMISSION` 这个 intent action。前者覆盖固定包名的旧版，后者覆盖随机包名的新版，**两者缺一都会在另一种形态上失灵**。

**关于 `INTERACT_ACROSS_USERS_FULL`**：`rikka.shizuku.ShizukuProvider` 在 Manifest 中以该权限作为 provider 的访问控制属性（限定仅本应用 / shell 身份可访问），**它不是本应用向系统申请的权限**，故不出现在上表中。

**不使用**：定位、通讯录、相机、麦克风、存储读写、剪贴板读取。

---

## 兼容性

- **最低支持**：Android 7.0（API 24）
- **目标版本**：Android 14（API 34）
- **已在实机验证**：OPPO / ColorOS 14（Android 14）

已知平台差异：

- Android 12+ 对后台启动前台服务有限制，Dollhouse 通过「用户主动点击启动」规避
- 部分厂商 ROM（如 ColorOS）会给前台服务发配置变化回调不稳定，项目已内置帧内自检兜底
- 厂商智能省电策略可能冻结后台进程，建议将 Dollhouse 加入电池优化白名单

---

## 隐私

- **不采集**：不收集、不上传任何用户数据
- **不联网**（除非你配置了 AI 接口）：未配置 API 时，应用自身不发起任何网络请求（Shizuku 本身也不需要联网）
- **凭据本地化**：API Key 仅存储在本机应用私有目录，不上传任何服务器
- **第三方 SDK**：除**可选的**开源 Shizuku 客户端库（RikkaApps，Apache-2.0，见[致谢](#致谢与第三方组件)）外，不含广告、统计、崩溃上报等任何第三方组件
- **Shizuku 能力默认关闭**：`shell` 命令执行需要你显式授权 Shizuku，且已授权的命令由**你发起的 AI 对话**触发；本应用不会在后台自行执行 shell 命令
- **Shizuku 命令的可见范围**：授权后经 `shell` 执行的命令及其输出，会作为对话内容发送给**你配置的第三方 AI 服务**（这是「AI 触发命令」的必经链路）；同时命令可读取系统中任意可读文件与设置，请自行评估敏感性
- **命令由模型生成**：`shell` 命令内容来自模型输出，可能存在误伤或破坏性操作（删除文件、修改系统设置等）。当前仅靠系统提示词约束，**没有命令白名单，也没有二次确认**，请谨慎使用

---

## 致谢与第三方组件

### Shizuku

本项目通过 [Shizuku](https://github.com/RikkaApps/Shizuku) 提供可选的系统级 `shell` 能力。

Shizuku 由 **RikkaApps（RikkaW）** 开发，采用 Apache License 2.0 开源。它让应用在**用户明确授权**的前提下，以 `adb`/`root` 身份调用系统 API，无需 root 即可获得受控的高权限能力。

- 项目主页：<https://github.com/RikkaApps/Shizuku>
- API 文档：<https://github.com/RikkaApps/Shizuku-API>
- 本项目使用其官方依赖：`dev.rikka.shizuku:api:13.1.5` 与 `dev.rikka.shizuku:provider:13.1.5`

> 特别致谢 RikkaApps 提供的这套权限框架 —— 没有它，这类「无需 root 的受控高权限」需求只能靠 root 或每台机器打补丁解决。

**注意**：Shizuku 是**独立安装**的第三方应用，不随本仓库分发。本仓库的 GPL-3.0 协议仅覆盖 Dollhouse 自身代码；Shizuku 二进制与其 API 依赖遵循其自身的 Apache-2.0 协议。

### 开源协议

本项目采用 **GNU General Public License v3.0（GPL-3.0）** 协议开源，详见 [LICENSE](LICENSE)。

这意味着你可以自由地使用、修改、分发本项目，但**衍生作品必须同样以 GPL-3.0 协议开源**，且必须保留原始版权声明。

```
Dollhouse - 桌面宠物应用
Copyright (C) 2026 Farewell-coder

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
