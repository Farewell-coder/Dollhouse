# Lazy 列表性能审计

> 依据：Google Compose 性能规范
> **"Always specify both primitive keys and contentType for all items in lazy layouts.
> Omitting contentType prevents Compose from reusing composition slots during scrolling."**

## 0. 数量修正（先纠正一处口径）

任务书说「Lazy 容器 23 处，有 contentType 3 处」。实测**容器是 8 个**：

```bash
grep -rn "LazyColumn(\|LazyRow(\|LazyVerticalGrid(\|LazyHorizontalGrid(\|LazyVerticalStaggeredGrid(" \
  app/src/main/java --include=*.kt | wc -l
# → 8
```

23 这个数字多半把 `items(` 之类调用一起数进去了。**真正需要逐项配 contentType 的容器是 8 个**，
另有 1 处 `for + item{}` 手铺列表（`MemPage:660`）也要算。

## 1. 总账

| 指标 | 前 | 后 |
|---|---|---|
| Lazy 容器总数 | 8 | 8 |
| 同时具备 **key + contentType** | 1（`ChatUi`，上一棒已修） | **8（全部）** |
| 只有 key、无 contentType | 7 | **0** |
| 无 key、无 contentType | 0 | 0 |

**结论：8/8 容器现在都同时具备 key 与 contentType。**

## 2. 逐处明细

| # | 位置 | 容器 | items 形态 | key | contentType | 形态分类 |
|---|---|---|---|---|---|---|
| 1 | `ChatUi.kt:451` | LazyColumn | 多形态 | ✓ | ✓（上一棒已修） | 按消息类型分 |
| 2 | `ChatListScreen.kt:304` | LazyColumn | `items(shown)` | ✓ `it.id` | ✅ **新增** `"conv"` | 单一：会话行 |
| 3 | `ConversationHistoryPage.kt:73` | LazyColumn | `items(entries)` | ✓ `it.first` | ✅ **新增** `"history"` | 单一：历史会话行 |
| 4 | `ModelListPage.kt:162` | LazyColumn | `items(shown)` | ✓ `it` | ✅ **新增** `"model"` | 单一：模型名行 |
| 5 | `MemPage.kt:344` | LazyColumn | `items(shownMems)` | ✓ `it.id` | ✅ **新增** `"mem"` | 单一：记忆行 |
| 6 | `MemPage.kt:660` | LazyColumn | `for + item{}` | ✓ `m.id` | ✅ **新增** `"hit"` | 单一：命中行 |
| 7 | `ContactScreen.kt:147` | LazyColumn | `items` + `item` | ✓ | ✅ **新增** 2 类 | **两种**：`"header"` 分组头 / `"contact"` 联系人行 |
| 8 | `DollListScreen.kt:184` | LazyVerticalGrid | `grid.items(dolls)` | ✓ `it.id` | ✅ **新增** `"doll"` | 单一：人偶卡 |

### 2.1 为什么「单一形态」也要写 contentType

这是最容易被忽略的一点。规范要求的是**所有** item 都声明 contentType，而不是「有多种形态才需要」。
原因：Lazy 的槽位复用按 contentType 归类。**完全不写**时 Compose 退化成「按 key 顺序推测」，
一旦列表里插入了不是同类的东西（比如 `ChatListScreen` 里那条 `DhDivider`、`ContactScreen` 的分组头），
复用链条就断在那一个位置，之后的项全部重建。

所以写法统一为「每个 item 显式声明自己的形态」：

- `items(...)` → `contentType = { "xxx" }`（lambda，返回任意对象）
- `item(...)` → `contentType = "xxx"`（**直接 String**，注意签名不同）

### 2.2 ContactScreen 为什么要分两类

它是唯一真正混合形态的容器：

```
for ((groupName, dolls) in groups) {
    item(key = "h_" + groupName, contentType = "header") { ... }   // 分组头（可折叠）
    if (!collapsed) {
        items(dolls, key = { "d_" + it.id }, contentType = { "contact" }) { ... }  // 联系人行
    }
}
```

两类条目的布局高度、内部结构都不同。共用一个 contentType 会让 Compose 尝试用分组头的槽位
去复用联系人行（或反之），既浪费又可能引发布局抖动。分开后各复用各的。

## 3. 规范其余三条的核查结果

### 3.1 item 里是否查 `LocalContext.current` / `LocalDensity.current`

**不在 item lambda 内取。** 全仓 `LocalContext.current` 共 9 处、`LocalDensity.current` 共 9 处，
逐处核对位置：

| 文件 | 行 | 是否在 Lazy item 内 |
|---|---|---|
| `ChatUi.kt` | 184 / 407 / 579 / 629 / 1143 | ❌ 都在 composable 函数体顶层，item 之外 |
| `DhKit.kt` | 321 / 392 | ❌ 组件函数体 |
| `DhTheme.kt` | 225 | ❌ 主题外壳 |
| `OperationRecorderButton.kt` | 23 | ❌ 单按钮 |
| `DollHomeOverlay.kt` / `DollRangeOverlay.kt` | 160 / 177 | ❌ 悬浮窗根 |
| `NavHost.kt` / `ProfileDrawer.kt` | 218 / 402 | ❌ 导航与抽屉根 |
| `DollHomePage.kt` / `ImportAssistantKit.kt` / `DollRangePage.kt` / `DhSwipe.kt` / `ComposeLayerShield.kt` | 111 / 622 / 100 / 55 / 74 | ❌ 均为组件顶层 |

**结论：0 处在 item lambda 内取这些 CompositionLocal，符合规范。** 无需改动。

### 3.2 是否有 `list.indexOf(item)`（应改 `itemsIndexed`）

全仓 `.indexOf(` 共 34 处，**逐处确认全是字符串操作**（`url.indexOf("://")`、`json.indexOf('{')` 等），
**没有一处**是在 Lazy item lambda 里对数据列表做线性查找。因此不需要改 `itemsIndexed`。

> 补充：`DollGroups.kt:111` 的 `list.indexOf(oldName)` 是分组重命名的数据层查找，
> 不在 Lazy 渲染路径上，无性能影响。

### 3.3 稳定性（stable keys）是否已满足

8 个容器**全部**已有 `key`，且都是**原始类型**（`String` / `Long` id），符合规范
「primitive keys」的要求。没有发现用对象实例或 `hashCode` 当 key 的情况。

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `ui/chat/ChatListScreen.kt` | `items` 加 `contentType = { "conv" }` |
| `ui/chat/ConversationHistoryPage.kt` | 加 `{ "history" }` |
| `ui/settings/ModelListPage.kt` | 加 `{ "model" }` |
| `ui/settings/MemPage.kt` | 两处：`items` 加 `{ "mem" }`；`item` 加 `"hit"` |
| `ui/home/ContactScreen.kt` | 两处：`item` 加 `"header"`；`items` 加 `{ "contact" }` |
| `ui/doll/DollListScreen.kt` | `grid.items` 加 `{ "doll" }` |

**未改动**：`ChatUi.kt`（上一棒已修，本次复核确认其写法正确）。

## 5. 验证

```
python3 tools/kotlin_precheck.py    → 检查 275 个文件：未发现问题
python3 tools/comment_balance.py    → 检查 275 个文件：全部平衡
python3 tools/symbol_refs.py --quiet → exit 0
bash tools/ui_audit.sh              → 总计 44 项：PASS 44 / FAIL 0
bash tools/anim_audit.sh            → 总计 101 项：PASS 101 / FAIL 0
```

复现命令：

```bash
# 容器总数
grep -rn "LazyColumn(\|LazyRow(\|LazyVerticalGrid(" app/src/main/java --include=*.kt | wc -l

# 有 contentType 的容器（应与容器总数一致）
grep -rn "contentType" app/src/main/java --include=*.kt | grep -v MemModels | grep -v "^.*//"
```

## 6. 说明与遗留

1. **没有真机验证**。contentType 的效果是「滚动时槽位复用率提升」，只能在真机上用
   Layout Inspector 或滚动帧率观察。本轮只做了静态保证：8/8 容器齐备。
2. **`ChatUi.kt` 的 contentType 我复核过**：它按消息类型（mine/theirs/系统行等）分类，
   是本仓唯一「真正多形态」且已正确分类的实现，可作为其余列表的范例。
3. **建议后续**：给 `ui_audit.sh` 加一条断言 —— 每个 `LazyColumn/LazyRow/LazyVerticalGrid`
   的最近 `items/item` 调用必须含 `contentType`。否则以后新加列表会再次漏掉（本轮就是这么漏的）。