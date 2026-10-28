# 图标映射表（Lucide）

> 本文件由 `tools/gen_icons_md.py` 从 `Icons.kt` 与 `res/drawable/` 生成，**不要手工编辑**。
> 改了图标只需重跑脚本，文档与代码永远同步。

## 规范

| 项 | 约定 |
|---|---|
| 来源 | [Lucide](https://lucide.dev)（ISC 协议），路径数据取自官方 SVG |
| 视口 | `24dp × 24dp`，`viewportWidth/Height = 24` |
| 线宽 | `strokeWidth = 2`，端点 / 拐角均为 `round` |
| 占位色 | `strokeColor = #FFFFFFFF`，**真色由 `Icons.get()/tint()` 在运行时按主题覆盖** |
| 命名 | 常量 `IC_XXX` 与资源 `ic_xxx` 一一对应，转换规则：小写 + 下划线 |
| 新增 | ① 放 `res/drawable/ic_xxx.xml`；② `Icons.kt` 加 `@JvmField val IC_XXX`；③ 重跑本脚本 |

当前共 **112** 个图标常量，**112** 个 `ic_*.xml` 资源。

## 按用途分组

同一个图标在不同页面承担相同语义。下面按**业务语义**归类，
新增界面时先在这里找有没有现成的，避免同一个意思用两个不同图标。

### 导航与方向

| 常量 | 资源 |
|---|---|
| `IC_CHEVRON_LEFT` | `ic_chevron_left` |
| `IC_CHEVRON_RIGHT` | `ic_chevron_right` |
| `IC_CHEVRON_DOWN` | `ic_chevron_down` |
| `IC_CHEVRON_UP` | `ic_chevron_up` |
| `IC_ARROW_LEFT` | `ic_arrow_left` |
| `IC_ARROW_RIGHT` | `ic_arrow_right` |
| `IC_STEP_BACK` | `ic_step_back` |
| `IC_STEP_FORWARD` | `ic_step_forward` |
| `IC_HOUSE` | `ic_house` |
| `IC_EXTERNAL` | `ic_external` |

### 增删改与文件

| 常量 | 资源 |
|---|---|
| `IC_PLUS` | `ic_plus` |
| `IC_MINUS` | `ic_minus` |
| `IC_CHECK` | `ic_check` |
| `IC_CLOSE` | `ic_close` |
| `IC_EDIT` | `ic_edit` |
| `IC_TRASH` | `ic_trash` |
| `IC_SAVE` | `ic_save` |
| `IC_COPY` | `ic_copy` |
| `IC_REFRESH` | `ic_refresh` |
| `IC_DOWNLOAD` | `ic_download` |
| `IC_ERASER` | `ic_eraser` |
| `IC_PACKAGE` | `ic_package` |
| `IC_PACKAGE_OPEN` | `ic_package_open` |
| `IC_PACKAGE_PLUS` | `ic_package_plus` |

### 人偶与人格

| 常量 | 资源 |
|---|---|
| `IC_WHALE` | `ic_whale` |
| `IC_TILE_PET` | `ic_tile_pet` |
| `IC_SPARKLES` | `ic_sparkles` |
| `IC_WAND_SPARKLES` | `ic_wand_sparkles` |
| `IC_HEART_HANDSHAKE` | `ic_heart_handshake` |
| `IC_BOOK_HEART` | `ic_book_heart` |
| `IC_QUOTE` | `ic_quote` |
| `IC_SIGNATURE` | `ic_signature` |
| `IC_USERS` | `ic_users` |
| `IC_USER_ROUND` | `ic_user_round` |
| `IC_USER_PLUS` | `ic_user_plus` |
| `IC_CIRCLE_USER` | `ic_circle_user` |

### 动作与动画

| 常量 | 资源 |
|---|---|
| `IC_PLAY` | `ic_play` |
| `IC_PAUSE` | `ic_pause` |
| `IC_SQUARE` | `ic_square` |
| `IC_BLOCKS` | `ic_blocks` |
| `IC_LAYERS` | `ic_layers` |
| `IC_SHUFFLE` | `ic_shuffle` |
| `IC_ROTATE_CCW` | `ic_rotate_ccw` |
| `IC_ROTATE_CW` | `ic_rotate_cw` |
| `IC_MOVE` | `ic_move` |
| `IC_GRAB` | `ic_grab` |
| `IC_HAND` | `ic_hand` |
| `IC_CROSSHAIR` | `ic_crosshair` |

### 外观与主题

| 常量 | 资源 |
|---|---|
| `IC_SUN` | `ic_sun` |
| `IC_PALETTE` | `ic_palette` |
| `IC_DROPLET` | `ic_droplet` |
| `IC_CONTRAST` | `ic_contrast` |
| `IC_APERTURE` | `ic_aperture` |
| `IC_IMAGE` | `ic_image` |
| `IC_IMAGE_PLUS` | `ic_image_plus` |
| `IC_LAYOUT_GRID` | `ic_layout_grid` |
| `IC_LAYOUT_TEMPLATE` | `ic_layout_template` |
| `IC_PANEL_RIGHT` | `ic_panel_right` |
| `IC_MAXIMIZE` | `ic_maximize` |
| `IC_MINIMIZE` | `ic_minimize` |
| `IC_ZOOM_IN` | `ic_zoom_in` |
| `IC_ZOOM_OUT` | `ic_zoom_out` |

### 聊天与社交

| 常量 | 资源 |
|---|---|
| `IC_CHAT` | `ic_chat` |
| `IC_MESSAGE_SQUARE` | `ic_message_square` |
| `IC_MESSAGE_SQUARE_HEART` | `ic_message_square_heart` |
| `IC_MESSAGES_SQUARE` | `ic_messages_square` |
| `IC_SEND` | `ic_send` |
| `IC_BELL` | `ic_bell` |
| `IC_MEGAPHONE` | `ic_megaphone` |
| `IC_NEWSPAPER` | `ic_newspaper` |
| `IC_MESSAGE_SQUARE_HEART` | `ic_message_square_heart` |

### AI 与模型

| 常量 | 资源 |
|---|---|
| `IC_BRAIN` | `ic_brain` |
| `IC_BOT` | `ic_bot` |
| `IC_SERVER` | `ic_server` |
| `IC_PLUG` | `ic_plug` |
| `IC_NETWORK` | `ic_network` |
| `IC_CABLE` | `ic_cable` |
| `IC_SPINNER` | `ic_spinner` |
| `IC_GAUGE` | `ic_gauge` |
| `IC_INFINITY` | `ic_infinity` |

### 设备与系统

| 常量 | 资源 |
|---|---|
| `IC_TERMINAL` | `ic_terminal` |
| `IC_SQUARE_TERMINAL` | `ic_square_terminal` |
| `IC_APP_WINDOW` | `ic_app_window` |
| `IC_MOUSE_POINTER_2` | `ic_mouse_pointer_2` |
| `IC_KEYBOARD` | `ic_keyboard` |
| `IC_CLIPBOARD` | `ic_clipboard` |
| `IC_SCAN` | `ic_scan` |
| `IC_SHIELD_CHECK` | `ic_shield_check` |
| `IC_KEY_ROUND` | `ic_key_round` |
| `IC_BATTERY_CHARGING` | `ic_battery_charging` |
| `IC_ACTIVITY` | `ic_activity` |
| `IC_WAYPOINTS` | `ic_waypoints` |

### 状态与诊断

| 常量 | 资源 |
|---|---|
| `IC_INFO` | `ic_info` |
| `IC_WARNING` | `ic_warning` |
| `IC_CIRCLE_ALERT` | `ic_circle_alert` |
| `IC_CHECK_CIRCLE` | `ic_check_circle` |
| `IC_X_CIRCLE` | `ic_x_circle` |
| `IC_EYE` | `ic_eye` |
| `IC_EYE_OFF` | `ic_eye_off` |
| `IC_CLOCK_3` | `ic_clock_3` |
| `IC_TAGS` | `ic_tags` |
| `IC_LINK_2` | `ic_link_2` |

### 文件夹与分类

| 常量 | 资源 |
|---|---|
| `IC_FOLDER_OPEN` | `ic_folder_open` |
| `IC_FOLDER_PLUS` | `ic_folder_plus` |
| `IC_FOLDER_TREE` | `ic_folder_tree` |
| `IC_LIBRARY` | `ic_library` |
| `IC_BOOK_OPEN` | `ic_book_open` |

## 清单

| 常量 | 资源 | 用途说明 | 引用处 |
|---|---|---|---|
| `IC_ACTIVITY` | `ic_activity` | 心跳折线（Lucide activity）：后台保活 / 运行状态。 | 3 处（`SettingsScreen.kt`, `DeveloperOptionsPage.kt`） |
| `IC_APERTURE` | `ic_aperture` | 光圈（Lucide aperture）：毛玻璃 / 模糊半径。 | 4 处（`DollLookTab.kt`, `AppearancePage.kt`） |
| `IC_APP_WINDOW` | `ic_app_window` | 设备控制能力。 | 2 处（`SettingsScreen.kt`, `Design.kt`） |
| `IC_ARROW_LEFT` | `ic_arrow_left` | — | 5 处（`ChatUi.kt`, `DhKit.kt`, `DollAnimPage.kt` 等 5 个文件） |
| `IC_ARROW_RIGHT` | `ic_arrow_right` | — | 1 处（`DollAnimPage.kt`） |
| `IC_BATTERY_CHARGING` | `ic_battery_charging` | 充电电池（Lucide battery-charging）：电池优化白名单。 | 1 处（`SettingsScreen.kt`） |
| `IC_BELL` | `ic_bell` | 铃铛（Lucide bell）：通知权限 / 消息提醒。 | 1 处（`SettingsScreen.kt`） |
| `IC_BLOCKS` | `ic_blocks` | 积木块（Lucide blocks）：动作模块 / 组件化的集合。 | 6 处（`DollAnimPage.kt`, `DollEditScreen.kt`, `DollListScreen.kt` 等 5 个文件） |
| `IC_BOOK_HEART` | `ic_book_heart` | 带心的书（Lucide book-heart）：人设 / 性格设定。 | 1 处（`DollPersonaPage.kt`） |
| `IC_BOOK_OPEN` | `ic_book_open` | 书本（Lucide book-open）：记忆库 / 知识库设置。 | 3 处（`DollPersonaPage.kt`, `SettingsScreen.kt`） |
| `IC_BOT` | `ic_bot` | 机器人（Lucide bot）：模型 / AI 能力相关设置。 | 2 处（`SettingsScreen.kt`, `VisionModelPage.kt`） |
| `IC_BRAIN` | `ic_brain` | — | 5 处（`ChatUi.kt`, `DollEditScreen.kt`, `ProfileDrawer.kt` 等 4 个文件） |
| `IC_CABLE` | `ic_cable` | 连线插头（Lucide cable）：服务连接 / 设备桥接语义。 | 3 处（`DollAnimPage.kt`, `LamdaPage.kt`） |
| `IC_CHAT` | `ic_chat` | 没有收口成常量。统一暴露后，调用点只认 Icons.IC_*，换图标只改一行。---- | 1 处（`DollPersonaPage.kt`） |
| `IC_CHECK` | `ic_check` | — | 14 处（`DollHomeOverlay.kt`, `DollRangeOverlay.kt`, `DhKit.kt` 等 11 个文件） |
| `IC_CHECK_CIRCLE` | `ic_check_circle` | — | 4 处（`DhKit.kt`, `DollImportWizardPage.kt`, `LamdaPage.kt` 等 4 个文件） |
| `IC_CHEVRON_DOWN` | `ic_chevron_down` | — | 8 处（`ChatUi.kt`, `DhKit.kt`, `DollAnimPage.kt` 等 7 个文件） |
| `IC_CHEVRON_LEFT` | `ic_chevron_left` | — | 4 处（`TokenStat.kt`, `DollAnimPage.kt`, `ApiPageKit.kt`） |
| `IC_CHEVRON_RIGHT` | `ic_chevron_right` | ---- 导航 ---- | 12 处（`TokenStat.kt`, `DollActionPage.kt`, `DollAnimPage.kt` 等 10 个文件） |
| `IC_CHEVRON_UP` | `ic_chevron_up` | — | 6 处（`ChatListScreen.kt`, `ChatUi.kt`, `DollAnimPage.kt` 等 5 个文件） |
| `IC_CIRCLE_ALERT` | `ic_circle_alert` | 服务 / 状态。 | 5 处（`DollImportWizardPage.kt`, `DeveloperOptionsPage.kt`, `Design.kt`） |
| `IC_CIRCLE_DOT` | `ic_circle_dot` | 节点（Lucide circle-dot）：图谱节点 / 记忆条目。 | 1 处（`MemPage.kt`） |
| `IC_CIRCLE_USER` | `ic_circle_user` | 我的头像（Lucide circle-user）：用户自身资料。 | 6 处（`ChatListScreen.kt`, `ChatUi.kt`, `ProfileDrawer.kt` 等 4 个文件） |
| `IC_CLIPBOARD` | `ic_clipboard` | — | 1 处（`Design.kt`） |
| `IC_CLOCK_3` | `ic_clock_3` | 时钟（Lucide clock-3）：时间 / 定时相关（暂离倒计时、自动保活周期）。 | 6 处（`DollEditScreen.kt`, `DollGesturePage.kt`, `DeveloperOptionsPage.kt`） |
| `IC_CLOSE` | `ic_close` | — | 11 处（`DollHomeOverlay.kt`, `DollRangeOverlay.kt`, `ChatListScreen.kt` 等 8 个文件） |
| `IC_CONTRAST` | `ic_contrast` | 对比度（Lucide contrast）：可读性遮罩强度。 | 1 处（`AppearancePage.kt`） |
| `IC_COPY` | `ic_copy` | — | 6 处（`ChatUi.kt`, `DollActionPage.kt`, `DollListScreen.kt` 等 4 个文件） |
| `IC_CROSSHAIR` | `ic_crosshair` | — | 7 处（`DollHomeOverlay.kt`, `DollAnimPage.kt`, `DollEditScreen.kt` 等 5 个文件） |
| `IC_DOWNLOAD` | `ic_download` | 安装 / 解包 / 下载。 | 8 处（`DollActionPage.kt`, `DollListScreen.kt`, `DollPersonaPage.kt` 等 6 个文件） |
| `IC_DROPLET` | `ic_droplet` | 水滴（Lucide droplet）：不透明度类滑条。 | 4 处（`DollLookTab.kt`, `AppearancePage.kt`） |
| `IC_EDIT` | `ic_edit` | — | 2 处（`DollListScreen.kt`, `AppearancePage.kt`） |
| `IC_ERASER` | `ic_eraser` | 橡皮（Lucide eraser）：清空固定壳的内容（只清帧，保留名字）。 | 2 处（`DollActionPage.kt`, `DollAnimPage.kt`） |
| `IC_EXTERNAL` | `ic_external` | — | 3 处（`DollCardIO.kt`, `DollPersonaPage.kt`, `ProviderListPage.kt`） |
| `IC_EYE` | `ic_eye` | — | 4 处（`DollEditScreen.kt`, `DollPersonaPage.kt`, `VisionModelPage.kt`） |
| `IC_EYE_OFF` | `ic_eye_off` | — | 2 处（`DollEditScreen.kt`, `SettingsScreen.kt`） |
| `IC_FOLDER_OPEN` | `ic_folder_open` | — | 4 处（`DollActionPage.kt`, `DollImportWizardPage.kt`, `DollListScreen.kt` 等 4 个文件） |
| `IC_FOLDER_PLUS` | `ic_folder_plus` | 新建文件夹（Lucide folder-plus）：新建分组。 | 1 处（`DollGroupPage.kt`） |
| `IC_FOLDER_TREE` | `ic_folder_tree` | 文件夹树（Lucide folder-tree）：分组层级 / 管理分组。 | 3 处（`DollGroupPage.kt`, `ContactScreen.kt`, `MemPage.kt`） |
| `IC_GAMEPAD_2` | `ic_gamepad_2` | 游戏手柄（Lucide gamepad-2）：设备控制类入口（lamda 设备控制）。 | 1 处（`SettingsScreen.kt`） |
| `IC_GAUGE` | `ic_gauge` | 仪表盘（Lucide gauge）：速度 / 性能类调节（动作速度）。 | 3 处（`DollAnimPage.kt`, `SettingsScreen.kt`） |
| `IC_GRAB` | `ic_grab` | 抓手（Lucide grab）：拖拽动作固定壳。 | 1 处（`DollActionPage.kt`） |
| `IC_HAND` | `ic_hand` | — | 6 处（`DollActionPage.kt`, `DollEditScreen.kt`, `DollGesturePage.kt` 等 4 个文件） |
| `IC_HEART_HANDSHAKE` | `ic_heart_handshake` | 握手（Lucide heart-handshake）：互动 / 陪伴语义。 | 1 处（`DollEditScreen.kt`） |
| `IC_HOUSE` | `ic_house` | — | 1 处（`Design.kt`） |
| `IC_IMAGE` | `ic_image` | — | 7 处（`DollAnimPage.kt`, `DollImportWizardPage.kt`, `DollListScreen.kt` 等 6 个文件） |
| `IC_IMAGE_PLUS` | `ic_image_plus` | 【r13】改封面（Lucide image-plus）：换一张封面图。 | 8 处（`ChatUi.kt`, `DollAnimPage.kt`, `DollListScreen.kt` 等 6 个文件） |
| `IC_INFINITY` | `ic_infinity` | 无限（Lucide infinity）：常驻动作固定壳。 | 2 处（`DollActionPage.kt`） |
| `IC_INFO` | `ic_info` | — | 4 处（`ChatUi.kt`, `ProfileDrawer.kt`, `LamdaPage.kt` 等 4 个文件） |
| `IC_KEYBOARD` | `ic_keyboard` | — | 1 处（`Design.kt`） |
| `IC_KEY_ROUND` | `ic_key_round` | 钥匙（Lucide key-round）：API 密钥 / 凭据。 | 2 处（`SettingsScreen.kt`, `ProviderEditPage.kt`） |
| `IC_LAYERS` | `ic_layers` | 模型集合 / 分层语义（Lucide layers）：详情页「模型」tab 与模型列表用。 | 6 处（`ChatUi.kt`, `DollImportWizardPage.kt`, `ProfileDrawer.kt` 等 6 个文件） |
| `IC_LAYOUT_GRID` | `ic_layout_grid` | 四宫格（Lucide layout-grid）：快捷设置磁贴 / 系统面板入口。 | 2 处（`SettingsScreen.kt`, `AppearancePage.kt`） |
| `IC_LAYOUT_TEMPLATE` | `ic_layout_template` | — | 2 处（`DollEditScreen.kt`, `DollListScreen.kt`） |
| `IC_LIBRARY` | `ic_library` | 图书馆（Lucide library）：动作库 / 内置资源库。 | 1 处（`DollListScreen.kt`） |
| `IC_LINK_2` | `ic_link_2` | 连线（Lucide link-2）：图谱关系边 / 手动关联。 | 3 处（`SettingsScreen.kt`, `DeveloperOptionsPage.kt`, `MemPage.kt`） |
| `IC_MAXIMIZE` | `ic_maximize` | 最大化（Lucide maximize）：图谱「适配屏幕」。 | 5 处（`DollAnimPage.kt`, `DollLookTab.kt`, `MemPage.kt`） |
| `IC_MEGAPHONE` | `ic_megaphone` | 喇叭（Lucide megaphone）：说说 / 公告。 | 1 处（`BottomNav.kt`） |
| `IC_MESSAGES_SQUARE` | `ic_messages_square` | 方形气泡组（Lucide messages-square）：聊天界面 Tab。 | 2 处（`DollLookTab.kt`, `AppearancePage.kt`） |
| `IC_MESSAGE_SQUARE` | `ic_message_square` | 聊天 Tab / 会话列表（Lucide message-square：方形气泡，比 message-circle 更像「列表」）。 | 6 处（`ChatListScreen.kt`, `DollEditScreen.kt`, `DollLookTab.kt` 等 6 个文件） |
| `IC_MESSAGE_SQUARE_HEART` | `ic_message_square_heart` | 聊天气泡与心（Lucide message-square-heart）：对话情感 / 对话显示策略。 | 4 处（`ChatUi.kt`, `SettingsScreen.kt`） |
| `IC_MINIMIZE` | `ic_minimize` | — | 1 处（`DollAnimPage.kt`） |
| `IC_MINUS` | `ic_minus` | — | 2 处（`DhKit.kt`, `DollAnimPage.kt`） |
| `IC_MORE` | `ic_more` | — | 3 处（`DollListScreen.kt`, `ProviderListPage.kt`） |
| `IC_MOUSE_POINTER_2` | `ic_mouse_pointer_2` | — | 2 处（`DollGesturePage.kt`, `Design.kt`） |
| `IC_MOVE` | `ic_move` | — | 3 处（`DollAnimPage.kt`, `DollEditScreen.kt`, `Design.kt`） |
| `IC_NETWORK` | `ic_network` | — | 1 处（`Design.kt`） |
| `IC_NEWSPAPER` | `ic_newspaper` | 报纸（Lucide newspaper）：说说动态流。 | 2 处（`ComingSoonPage.kt`） |
| `IC_PACKAGE` | `ic_package` | 打包箱（Lucide package）：模型包 / 素材包（与 package-open 是「箱合 vs 箱开」两个状态）。 | 3 处（`DollAnimPage.kt`, `DollCardIO.kt`, `DeveloperOptionsPage.kt`） |
| `IC_PACKAGE_OPEN` | `ic_package_open` | — | 2 处（`Design.kt`） |
| `IC_PACKAGE_PLUS` | `ic_package_plus` | — | 1 处（`Design.kt`） |
| `IC_PALETTE` | `ic_palette` | 调色板（Lucide palette）：外观页整体入口 / 主题 Tab。 | 8 处（`DollEditScreen.kt`, `DollLookTab.kt`, `ProfileDrawer.kt` 等 5 个文件） |
| `IC_PANEL_RIGHT` | `ic_panel_right` | 面板靠右（Lucide panel-right）：贴边动作固定壳。 | 2 处（`DollActionPage.kt`, `AppearancePage.kt`） |
| `IC_PAUSE` | `ic_pause` | — | 1 处（`DollAnimPage.kt`） |
| `IC_PLAY` | `ic_play` | 启停 / 重启。 | 4 处（`DollAnimPage.kt`, `DollListScreen.kt`, `ProfileDrawer.kt` 等 4 个文件） |
| `IC_PLUG` | `ic_plug` | 连接 / 网络 / 终端。 | 8 处（`DollPersonaPage.kt`, `ProviderEditPage.kt`, `Design.kt`） |
| `IC_PLUS` | `ic_plus` | — | 14 处（`DhKit.kt`, `DollActionPage.kt`, `DollAnimPage.kt` 等 11 个文件） |
| `IC_QUOTE` | `ic_quote` | 引号（Lucide quote）：个性签名。 | 3 处（`DollAnimPage.kt`, `DollPersonaPage.kt`, `ProfileDrawer.kt`） |
| `IC_REFRESH` | `ic_refresh` | — | 5 处（`ChatUi.kt`, `DollPersonaPage.kt`, `AboutPage.kt` 等 4 个文件） |
| `IC_ROTATE_CCW` | `ic_rotate_ccw` | — | 5 处（`DollHomeOverlay.kt`, `DollRangeOverlay.kt`, `DollListScreen.kt` 等 5 个文件） |
| `IC_ROTATE_CW` | `ic_rotate_cw` | — | 1 处（`DollRangeOverlay.kt`） |
| `IC_SAVE` | `ic_save` | 保存（Lucide save）：把当前编辑落盘并返回。 | 2 处（`MyProfilePage.kt`, `Design.kt`） |
| `IC_SCAN` | `ic_scan` | — | 1 处（`Design.kt`） |
| `IC_SEND` | `ic_send` | ---- 操作 ---- | 2 处（`ChatUi.kt`, `UiKit.kt`） |
| `IC_SERVER` | `ic_server` | 所以这里不另开常量，沿用 IC_WARNING，避免两个名字指同一个东西。 | 2 处（`SettingsScreen.kt`, `Design.kt`） |
| `IC_SETTINGS` | `ic_settings` | — | 4 处（`DollAnimPage.kt`, `ProfileDrawer.kt`, `ProviderDetailPage.kt` 等 4 个文件） |
| `IC_SHIELD_CHECK` | `ic_shield_check` | 盾牌对勾（Lucide shield-check）：安全 / 已校验 / 凭据保护。 | 5 处（`DollImportWizardPage.kt`, `SettingsScreen.kt`, `AboutPage.kt` 等 4 个文件） |
| `IC_SHUFFLE` | `ic_shuffle` | 洗牌（Lucide shuffle）：随机动作组。 | 4 处（`DollActionPage.kt`, `DollEditScreen.kt`） |
| `IC_SIGNATURE` | `ic_signature` | 【r13】命名（Lucide signature）：给动作 / 人偶改名字。 | 6 处（`ChatListScreen.kt`, `DollAnimPage.kt`, `DollEditScreen.kt` 等 6 个文件） |
| `IC_SLIDERS_HORIZONTAL` | `ic_sliders_horizontal` | — | 3 处（`DollEditScreen.kt`, `DollPersonaPage.kt`, `ProviderEditPage.kt`） |
| `IC_SPARKLES` | `ic_sparkles` | — | 9 处（`DollActionPage.kt`, `DollEditScreen.kt`, `SettingsScreen.kt` 等 8 个文件） |
| `IC_SPINNER` | `ic_spinner` | — | 3 处（`LamdaPage.kt`, `Design.kt`） |
| `IC_SQUARE` | `ic_square` | — | 3 处（`DollListScreen.kt`, `ProfileDrawer.kt`, `Design.kt`） |
| `IC_SQUARE_STACK` | `ic_square_stack` | 卡片堆叠（Lucide square-stack）：弹窗与卡片 Tab。 | 4 处（`AppearancePage.kt`） |
| `IC_SQUARE_TERMINAL` | `ic_square_terminal` | — | 1 处（`Design.kt`） |
| `IC_STEP_BACK` | `ic_step_back` | — | 1 处（`DollAnimPage.kt`） |
| `IC_STEP_FORWARD` | `ic_step_forward` | — | 1 处（`DollAnimPage.kt`） |
| `IC_SUN` | `ic_sun` | — | 4 处（`SettingsScreen.kt`, `AppearancePage.kt`, `Design.kt`） |
| `IC_TAGS` | `ic_tags` | 标签组（Lucide tags）：分组 / 分类标记。 | 3 处（`DollAnimPage.kt`, `DollGroupPage.kt`, `DeveloperOptionsPage.kt`） |
| `IC_TERMINAL` | `ic_terminal` | — | 2 处（`DeveloperOptionsPage.kt`, `Design.kt`） |
| `IC_TILE_PET` | `ic_tile_pet` | — | 1 处（`HomeUi.kt`） |
| `IC_TRASH` | `ic_trash` | — | 16 处（`ChatListScreen.kt`, `ChatUi.kt`, `DhSwipe.kt` 等 14 个文件） |
| `IC_USERS` | `ic_users` | 人偶 Tab（Lucide users：双人）。 | 5 处（`BottomNav.kt`, `ContactScreen.kt`, `SettingsScreen.kt` 等 4 个文件） |
| `IC_USER_PLUS` | `ic_user_plus` | 添加人偶（Lucide user-plus）。 | 1 处（`ContactScreen.kt`） |
| `IC_USER_ROUND` | `ic_user_round` | — | 4 处（`ChatListScreen.kt`, `DollEditScreen.kt`, `DollGroupPage.kt` 等 4 个文件） |
| `IC_WAND_SPARKLES` | `ic_wand_sparkles` | 魔法棒（Lucide wand-sparkles）：AI 生成 / 智能配置。 | 1 处（`SettingsScreen.kt`） |
| `IC_WARNING` | `ic_warning` | — | 5 处（`DhKit.kt`, `AppearancePage.kt`, `DeveloperOptionsPage.kt` 等 5 个文件） |
| `IC_WAYPOINTS` | `ic_waypoints` | 路径点（Lucide waypoints）：记忆图谱主干入口。 | 3 处（`DollAnimPage.kt`, `MemPage.kt`） |
| `IC_WHALE` | `ic_whale` | — | 4 处（`ChatListScreen.kt`, `DollGroupPage.kt`, `ContactScreen.kt` 等 4 个文件） |
| `IC_X_CIRCLE` | `ic_x_circle` | — | 2 处（`DhKit.kt`, `Design.kt`） |
| `IC_ZOOM_IN` | `ic_zoom_in` | 放大（Lucide zoom-in）：图谱放大。 | 1 处（`MemPage.kt`） |
| `IC_ZOOM_OUT` | `ic_zoom_out` | 缩小（Lucide zoom-out）：图谱缩小。 | 1 处（`MemPage.kt`） |

