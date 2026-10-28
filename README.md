<div align="center">

<br>

<img src="assets/icon.png" width="108" height="108" alt="Dollhouse">

# Dollhouse

**一间亮着灯的小房子，等着喜欢的角色住进来。**

<br>

<a href="https://github.com/Farewell-coder/Dollhouse/releases/latest"><img src="https://img.shields.io/badge/下载-Releases-6C5CE7?style=flat-square&logo=android&logoColor=white" alt="下载 Releases"></a>
&nbsp;
<a href="#快速开始"><img src="https://img.shields.io/badge/Android-7.0+-F3F1FF?style=flat-square&logo=android&logoColor=6C5CE7" alt="Android 7.0+"></a>
&nbsp;
<a href="LICENSE"><img src="https://img.shields.io/badge/开源-GPL--3.0-F3F1FF?style=flat-square&logo=opensourceinitiative&logoColor=6C5CE7" alt="GPL-3.0"></a>
&nbsp;
<a href="README.en.md"><img src="https://img.shields.io/badge/语言-English-F3F1FF?style=flat-square" alt="English"></a>

</div>

---

有时候，我只是希望手机里能多一点生气。

不是多一个催促我的工具，也不是多一个需要完成的任务，而是一个安静待在屏幕角落的小家伙。闲下来时看它眨眨眼，点它一下会有回应，忽然想说几句话，也不用特意去找谁。

Dollhouse 就是从这样的念头开始的。

做着做着，我又觉得，桌宠不该永远只有一张脸、几句固定的台词。它应该能慢慢变成你喜欢的样子：有自己的角色、动作，也记得你们聊过什么。

所以，Dollhouse 不只是一张会动的图片，也不只是一个套上 AI 的聊天窗口。我更想把它做成一间可以慢慢布置的小房子——**房子是平台，住在里面的角色，才是故事的开始。**

目前住着的是鲸鱼娘「小肥鱼」。她会眨眼、待机摆动，也会贴着屏幕边缘探出头来。

## 这间房子里有什么

### 一个会回应你的小家伙

角色可以悬浮在其他应用之上，不必每次想见它都先打开 Dollhouse。

- 拖动、贴边吸附、位置保存和大小调整
- 待机、动画与屏幕边缘互动
- 点击弹出台词气泡，三击唤出迷你输入框
- 前台服务、开机恢复与后台保活设置

### 想说话时，它就在

迷你输入框和完整聊天界面共用同一场对话，想到哪里就聊到哪里，回头也能找到之前说过的话。

支持多轮对话、流式回复和会话历史。你可以管理多套模型服务、模型列表与收藏，也可以设置自己的头像、昵称和个性签名。

AI 对话需要自行配置联网服务，项目不内置离线模型。实际能力取决于你选择的服务与模型。

### 让那些话不被轻易忘记

会话自动总结、长期记忆保存、记忆自动精简，是三个可以分别决定的开关。

记忆库不是一排冰冷的列表，而是一张可以拖拽、缩放的力导向图谱。点节点看一段记忆，点连线看它们之间的关系，也可以亲手把两段记忆连起来。

### 按你的喜好慢慢布置

跟随系统、浅色、深色、纯黑主题，支持壁纸取色、背景图与毛玻璃。背景分为人偶层和全局层两级回落，也可以给单个角色单独设置聊天背景和气泡颜色。

角色与动作也会继续完善，让不同的角色有不同的表现，而不是换一张图就结束。

### 出一些状况时，也别靠猜

关于页最后一项是诊断中心。这里可以看到错误与崩溃分组、异常堆栈和关联日志，也能搜索、筛选、执行日志自检，或导出经过完整性校验的 ZIP 诊断包。

严重错误会保留基础记录，详细运行日志则可以单独开关。

## 快速开始

1. 从 [Releases](https://github.com/Farewell-coder/Dollhouse/releases) 下载 APK，建议优先选择稳定版本。
2. 安装并打开应用，授予悬浮窗权限，启动桌宠；通知权限用于显示运行通知。
3. 如果想聊天，在设置里填入自己的模型服务配置。
4. 如果后台经常被系统关闭，可以按应用内的引导调整电池优化与厂商自启动选项。

最低支持 **Android 7.0（API 24）**，目标 **Android 14（API 34）**。项目已在 OPPO / ColorOS 14 上构建和装机验证，但这不代表所有厂商与系统版本都已验证。

设备操作是可选的扩展能力，需要自行安装、启动并授权 [Shizuku](https://github.com/RikkaApps/Shizuku)。[lamda](https://github.com/firerpa/lamda) 同样是可选组件，不随 APK 分发。没有它们，桌宠和已配置的聊天依然可以正常使用。

## 关于权限与数据

有些权限是为了让它正常陪伴你，有些则只在明确需要时才用到：

- 悬浮窗用于人偶和聊天窗口；前台服务与通知用于后台运行；开机广播用于恢复人偶。
- 网络用于你配置的对话服务、联网工具及可选组件下载。
- 对话、记忆和配置保存在本机应用私有目录。联网聊天时，相关内容会按功能需要发送给你所配置的服务。
- 凭据只用于请求认证。导出或分享配置前，请检查是否包含敏感信息。
- OCR 使用 Google ML Kit，相关依赖遵循各自的条款与数据处理规则。
- 授权后的设备命令可能读取界面、文件和设置，也可能修改设备状态。模型生成的指令可能出错，重要操作请自行核实。

## 更新公告

每个版本添了什么、改了什么、修好了什么，都认真记在更新记录里，不再在这里堆成一长串。

欢迎前往 [更新记录](CHANGELOG.md) 查看。

## 从源码构建

需要 JDK 17、Android SDK 36；工程使用 Gradle 8.11.1、AGP 8.9.1、Kotlin 2.2.10，源码为纯 Kotlin。

```bash
git clone https://github.com/Farewell-coder/Dollhouse.git
cd Dollhouse

# 在 local.properties 中配置本机 SDK 路径。
# gradle.properties 含手机 arm64 构建的本地 aapt2 路径；
# 普通桌面环境须移除该覆盖项，或改成可用的本机路径。
./gradlew assembleDebug
```

发布签名请参照 `keystore.properties.example` 配置自己的密钥，再运行 `./gradlew assembleRelease`。产物位于 `app/build/outputs/apk/`。

签名凭据和构建产物不入库；自行签名的包不能直接覆盖官方签名包。

## 一起把房子修好

Dollhouse 是开源项目，也是一个仍在慢慢生长的想法。

如果你喜欢这个方向，欢迎阅读源码、反馈问题、提出建议或提交代码。让角色的动作自然一点，让页面返回可靠一点，让内存占用少一点——这些具体的小改进，都会让这间房子住起来更舒服。

- [GitHub 仓库](https://github.com/Farewell-coder/Dollhouse)
- [版本与安装包](https://github.com/Farewell-coder/Dollhouse/releases)
- [问题反馈与建议](https://github.com/Farewell-coder/Dollhouse/issues)

## 第三方声明与许可

| 来源 | 用途 | 许可或条款 |
| --- | --- | --- |
| [Lucide](https://github.com/lucide-icons/lucide) | 图标路径的 Android 适配 | ISC，Feather 衍生部分为 MIT |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | 授权与系统接口客户端 | MIT；不等同于独立管理器的许可 |
| Kotlin、协程、Hilt / Dagger、AndroidX 等 | 运行时及依赖 | Apache-2.0 等，逐项见清单 |
| Google ML Kit / Play services | 文字识别及其依赖 | Google ML Kit / Android SDK 条款 |

完整版权、许可及构建依赖清单见 [第三方声明](THIRD_PARTY_NOTICES.md) 和 [licenses](licenses/)。

Dollhouse 自有代码采用 [GPL-3.0](LICENSE)，Copyright (C) 2026 Farewell-coder；第三方内容遵循各自许可。

本项目按“现状”提供，不保证适配所有设备。AI 回复及设备操作可能出错，重要信息和高权限操作请自行核实。此说明不减少适用开源许可赋予的权利，也不排除法律规定不可免除的责任。

---

<div align="center">

房子还在慢慢修，故事也还没有写完。

**欢迎来到 Dollhouse。**

</div>