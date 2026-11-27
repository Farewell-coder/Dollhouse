# QZX UI 系统迁移清单

> 目标：**保留功能不变**，把全部界面从命令式 `DhKit` 迁到声明式 `Qzx*`，最后删除旧系统。
> 新系统：`app/src/main/java/com/dollhouse/app/ui/qzx/`

---

## 迁移范式

### 旧（命令式）
```kotlin
DhKit.Page(title = "设置", onBack = { back() }) {
    DhGroup(collapsibleTitle = "外观") {
        DhKit.ActionRow(title = "主题", value = label, onClick = { open() })
        DhKit.RowDivider(insetStart = INSET)
        DhKit.SwitchRow(title = "跟随系统", checked = on, onChecked = { ... })
    }
}
```

### 新（声明式）
```kotlin
QzxPage(title = "设置", onBack = { back() }) {
    QzxList {
        section("appearance", title = "外观") {
            item("主题", label).arrow().onClick { open() }
            switch("跟随系统", checked = on).onChecked { ... }
        }
    }
}
```

**判断迁移是否成功**：页面代码里**不再出现** `padding(...)` / `height(...)` / `border(...)` / `RowDivider` / `Divider`。
排版全部由系统算。如果还要手写这些，说明新系统缺能力，**报上来补**。

---

## 分批（按「声明式适配度」排序）

### 批 1 — 纯列表页（最容易，声明式收益最大）
| 页面 | 状态 |
|---|---|
| `home/SettingsScreen.kt` | 🔄 试点中（003） |

### 批 2 — 设置子页（15 个，绝大多数是列表）
`settings/` 下的页面：
AboutPage · AppearancePage · ChatSettingsPage · DeveloperOptionsPage ·
MemPage · MemSettingsPage · ModelEditPage · MyProfilePage · PermissionPage ·
SleepSettingsPage · VisionModelPage · LamdaPage · FriendAppearancePage ·
FriendModelConfigPage · SettingsSearchPage

### 批 3 — 供应商 / 模型页（4 个）
ProviderEditPage · ProviderListPage · ProviderModelsPage · ProviderDetailPage

### 批 4 — 人偶页（16 个，多为自定义布局）
DollListScreen · DollEditScreen · DollPersonaPage · DollActionPage ·
DollAnimPage · DollGroupPage · DollImportWizardPage · ModelManagePage ·
ReplaceModelPage · DollHomePage · DollRangePage · DollReturnPage ·
DollAwayPage · DollTalkPage · DollGesturePage · ImportAssistantPage

### 批 5 — 聊天页（4 个，自定义布局最多）
ChatUi · ChatListScreen · ConversationHistoryPage · FriendProfilePage

---

## 每页的验收标准

| # | 检查项 |
|---|---|
| 1 | **功能入口数量**：改前 N 个，改后必须还是 N 个 |
| 2 | **当前值显示**：每行右侧的值还在 |
| 3 | **`SettingsSpots` 登记**：设置搜索仍能找到这页的项 |
| 4 | **导航调用**：`PageStack.push` / `ProviderNav.open` 原样 |
| 5 | **宽屏两栏**：若有 `SettingsTwoPane`，保留 |
| 6 | **无手写排版**：除 `custom { }` 逃生口外，不出现 padding/height/border |
| 7 | 门禁：`kotlin_precheck` / `lazy_nest_audit` / `palette_contrast` 全绿 |

---

## 分阶段推进

```
阶段 1（当前）：设置页试点 + 补齐 QzxList.spot / QzxNavTransitions
阶段 2：批 2 设置子页 15 个（每 5 个一批，每批跑门禁）
阶段 3：批 3 供应商 4 个
阶段 4：批 4 人偶 16 个
阶段 5：批 5 聊天 4 个
阶段 6：删除旧系统（DhKit / DhForm / Components / dhUI 等）
```

**阶段 6 是唯一危险的一步** —— 必须在「0 处引用旧系统」之后才能做。
验收：`grep -rn "ui.compose.DhKit\|ui.kit.Components\|ui.compose.DhForm" app/src/main/` 返回 0。

---

## 已知缺口（需补进新系统）

| 缺口 | 说明 | 归属 |
|---|---|---|
| `QzxNavTransitions` | 分层转场（前进 0.92 缩小变暗 / 返回镜像） | 002 |
| `QzxList.spot(id)` | 设置搜索的索引登记 | 002 |
| **折叠分组** | 旧 `DhGroup(collapsible = true)` 的折叠卡片 | 待定 |
| **两栏布局** | 宽屏 `SettingsTwoPane` | 待定 |
| **对话框** | 旧 `DhForm.Alert` / `AppDialog` 的 QZX 等价物 | 待定 |
| **表单控件** | `QzxList` 的 input 是否够用（密码框/多行/数字） | 待定 |
