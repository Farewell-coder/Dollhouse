## 修复：HTTP 400 Invalid max_tokens value

发消息直接失败的元凶。DeepSeek 下每句话都回：

```
HTTP 400: Invalid max_tokens value, the valid range of max_tokens is [1, 393216]
```

**根因是三段代码各说各话：**

1. `EffectiveChatConfig.maxTokens` 在「用户没配」时兜底成 `0`，而 `0` 在这个工程里的既有语义是「不发这个字段」；
2. 但 `samplingParams()` 会**无条件**把 `cfg.maxTokens` 写进请求体，把 `0` 也写进去了；
3. `buildBody()` 只摘掉**负数**，漏掉了 `0`。

于是 `max_tokens: 0` 原样发出，服务端要求 ≥ 1，直接判越界。

**附带发现：** `MAX_TOKENS_LIMIT = 1_000_000` 是拍脑袋定的，比 DeepSeek 的真实上限 `393216` 大了 2.5 倍 —— 这道本该拦住荒唐值的保护阀，本身就放行了会 400 的值，等于没做。

**修法：**

- `MAX_TOKENS_LIMIT` 由 `1_000_000` 收到 `393_216`（当前已知最严的服务端上限）
- 新增 `DEFAULT_MAX_TOKENS = 8_192`，用户没配时下发这个合法值，不再兜底成 `0`（约 5000～6000 汉字，够长，且远低于任何一家硬上限）
- `buildBody()` 的摘除判据由 `< 0` 改为 `<= 0`，作为最后一道闸 —— 即使上游再出纰漏，非法值也不会被发出去

## 修复：诊断中心不记录接口报错

**现象：** 聊天页弹出 `HTTP 400: …`，但「设置 → 诊断中心 → 日志」里一条都没有。拿着报错去问，也说不清是什么请求、打到哪、原始响应长什么样。

**根因：** 非 2xx 分支只把错误拼成字符串交给上层弹气泡，**全程没有一次落盘**。诊断库里 ERROR 级只会被动收到「崩溃」和 keep-alive 失败，HTTP 4xx/5xx 根本不在其中。

而原有的 `Logs.i` 是 INFO 级 —— 日志开关默认关闭时，`DiagnosticsStore.record()` 第一行就早退丢弃了，所以以前即使打了 INFO 也留不下来。

**修法：** 在三个出口补 ERROR 级记录（ERROR 级不受日志开关影响，永远落盘）：

- **非流式非 2xx**：记 HTTP 码、真实请求地址、当次 `max_tokens` 值、响应体前 500 字
- **非流式异常**（连不上 / 超时 / 解析崩）：记异常类型与端点
- **流式异常**：同样记录。用户主动停止（`__STOPPED__`）不算故障，跳过以免刷屏

记录内容经 `DiagnosticsFormat.redact` 脱敏，Bearer token、`sk-` 密钥、用户目录一律替换为占位符。

## 其他

- 同步更新 `PersonaChatConfigTest` 的一条断言：原断言 `assertEquals(0, eff.maxTokens)` 锁定的是「没配 = 0」的旧语义，不改会红

## 下载

| 包 | 适用机型 |
|---|---|
| `app-arm64-v8a-release.apk` | 绝大多数手机，优先装这个 |
| `app-armeabi-v7a-release.apk` | 较老的 32 位设备 |
| `app-universal-release.apk` | 不确定机型时选它（体积大但通用） |

由 GitHub Actions 自动构建，使用正式签名证书，**可直接覆盖安装升级，聊天记录与人偶配置不会丢**。

---

版本：`0.2.1`（versionCode 21）· 上一版 `0.2.0`
