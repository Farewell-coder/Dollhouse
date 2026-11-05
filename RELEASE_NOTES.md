## 新增：Aivora 深链一键导入

Aivora 0.0.8 新增了 `dollhouse://aivora-import` 深链：在 Aivora 里选中一条配置 → 点「深链」→ 选 Dollhouse，配置直接送过来。

本版接收并解析这条明文链接：

- 弹确认页，标明「来源：Aivora 外部链接（明文）」，显示地址与密钥尾 4 位
- 用户点「导入」后才写入；不会静默保存
- 缺参数、乱码、JSON 损坏、kind 不匹配、版本过新、内容不全、超长都有明确提示
- 不把链接、`d` 参数或 API Key 写进日志

## 变更：AEX 1.1 文件导入

Aivora 0.0.8 同时把 `.akcp` 升到 AEX 1.1：

- 接收 1.1 文件；仍兼容读取旧的 1.0 文件
- 1.1 不再携带模型列表，导入成功后自动请求 `{baseUrl}/models` 补拉
- 拉取失败只提示「可稍后手动添加」，不会撤销已经导入的供应商
- 官方 1.1 测试向量已纳入单测

## 其他

- Aivora 生态页 `protocol/ECOSYSTEM.md` 已登记 Dollhouse（文件+深链、AEX 1.1、自行声明）
- 版本号 `0.2.2`（versionCode 22）

## 下载

| 包 | 适用机型 |
|---|---|
| `app-arm64-v8a-release.apk` | 绝大多数手机，优先装这个 |
| `app-armeabi-v7a-release.apk` | 较老的 32 位设备 |
| `app-universal-release.apk` | 不确定机型时选它（体积大但通用） |

由 GitHub Actions 自动构建，使用正式签名证书，**可直接覆盖安装升级，聊天记录与人偶配置不会丢**。

---

版本：`0.2.2`（versionCode 22）· 上一版 `0.2.1`
