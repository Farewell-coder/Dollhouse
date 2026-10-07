# 第三方版权与许可

Dollhouse 自有代码使用 GPL-3.0。下列代码、图标及依赖分别遵循自身许可，项目许可不替代第三方条款。这里只列实际使用内容，纯思路参考不列。

## 代码与图标

- Lucide：图标路径适配为 Android VectorDrawable；完整 ISC 及 Feather 衍生部分 MIT 文本见 [Lucide-LICENSE](licenses/Lucide-LICENSE.txt)。同时保留之前随项目附带的 [版权文本](licenses/Lucide-legacy-ISC.txt)。
- morphicons：Spring.java 从弹簧积分器移植，Springs.java 采用弹簧预置参数，并适配 Android 驱动；见 [MIT](licenses/morphicons-MIT.txt)。
- Shizuku API 13.1.5（api / aidl / shared / provider）：客户端依赖适用 [MIT](licenses/Shizuku-API-MIT.txt)。独立 Shizuku 管理器不随 APK 分发，其许可另行适用。
- Hilt / Dagger、AndroidX、Kotlin / 协程、Guava listenablefuture、javax.inject、JSR305 与部分 Google 开源基础组件：见 [Apache-2.0](licenses/Apache-2.0.txt) 及所附逐组件版权文件。
- Google ML Kit / Play services / ODML：适用 [ML Kit 服务条款](https://developers.google.com/ml-kit/terms) 或 [Android SDK 条款](https://developer.android.com/studio/terms.html)，并非都以开源许可发布。其实际构建 AAR 内附带的第三方许可原文与索引已完整提取至 licenses/bundled/，不得因首页缩短而删除这些文本。

Kotlin 附带的 [LICENSE](licenses/Kotlin-LICENSE.txt) 与 [NOTICE](licenses/Kotlin-NOTICE.txt)、协程 [LICENSE](licenses/Coroutines-LICENSE.txt)、Dagger [LICENSE](licenses/Dagger-LICENSE.txt) 和 Guava [LICENSE](licenses/Guava-LICENSE.txt) 同时保留。构建工具的编译器声明与运行时组件适用范围以对应上游文件为准。

lamda 是运行时按需下载的外部工具，不随此 APK 分发，遵循其自身许可。人偶素材与原桌宠代码由项目所有者确认自有。

## 实际发行构建依赖

清单来自本次 release 构建的 SDK 依赖元数据及对应本地 Maven POM，包含传递依赖；列入不代表 R8 后每个模块均保留全部代码。POM 缺许可字段的 Apache-2.0 组件另保留通用许可，具体权利以组件原始声明为准。

| 组件 | POM 许可/条款 | 内附版权文件 |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib:1.9.24` | The Apache License, Version 2.0 | 未附独立文件 |
| `org.jetbrains:annotations:23.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `dev.rikka.shizuku:api:13.1.5` | MIT License | 未附独立文件 |
| `dev.rikka.shizuku:aidl:13.1.5` | MIT License | 未附独立文件 |
| `dev.rikka.shizuku:shared:13.1.5` | MIT License | 未附独立文件 |
| `dev.rikka.shizuku:provider:13.1.5` | MIT License | 未附独立文件 |
| `com.google.mlkit:text-recognition-chinese:16.0.1` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.mlkit-text-recognition-chinese-16.0.1-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.mlkit-text-recognition-chinese-16.0.1-third_party_licenses.txt) |
| `com.google.android.gms:play-services-base:18.5.0` | Android Software Development Kit License | [third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-base-18.5.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-base-18.5.0-third_party_licenses.txt) |
| `androidx.collection:collection:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.annotation:annotation:1.3.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.core:core:1.9.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.annotation:annotation-experimental:1.3.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.concurrent:concurrent-futures:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.guava:listenablefuture:1.0` | POM未标注；参见组件许可 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime:2.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.arch.core:core-common:2.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.arch.core:core-runtime:2.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-common:2.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.core:core-ktx:1.9.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.fragment:fragment:1.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.activity:activity:1.6.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel:2.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-livedata-core:2.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.savedstate:savedstate:1.2.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.tracing:tracing:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.loader:loader:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-livedata:2.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.viewpager:viewpager:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.customview:customview:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.android.gms:play-services-basement:18.4.0` | Android Software Development Kit License | [third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-basement-18.4.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-basement-18.4.0-third_party_licenses.txt) |
| `com.google.android.gms:play-services-tasks:18.2.0` | Android Software Development Kit License | [third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-tasks-18.2.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-tasks-18.2.0-third_party_licenses.txt) |
| `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-chinese-16.0.1-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-chinese-16.0.1-third_party_licenses.txt) |
| `com.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-common-19.1.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-common-19.1.0-third_party_licenses.txt) |
| `com.google.android.datatransport:transport-api:2.2.1` | The Apache Software License, Version 2.0 | [third_party_licenses.json](licenses/bundled/com.google.android.datatransport-transport-api-2.2.1-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.datatransport-transport-api-2.2.1-third_party_licenses.txt) |
| `com.google.android.datatransport:transport-backend-cct:2.3.3` | The Apache Software License, Version 2.0 | [third_party_licenses.json](licenses/bundled/com.google.android.datatransport-transport-backend-cct-2.3.3-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.datatransport-transport-backend-cct-2.3.3-third_party_licenses.txt) |
| `com.google.android.datatransport:transport-runtime:2.2.6` | The Apache Software License, Version 2.0 | [third_party_licenses.json](licenses/bundled/com.google.android.datatransport-transport-runtime-2.2.6-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.datatransport-transport-runtime-2.2.6-third_party_licenses.txt) |
| `javax.inject:javax.inject:1` | POM未标注；参见组件许可 | 未附独立文件 |
| `com.google.firebase:firebase-encoders:16.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.firebase:firebase-encoders-json:17.1.0` | The Apache Software License, Version 2.0 | [third_party_licenses.json](licenses/bundled/com.google.firebase-firebase-encoders-json-17.1.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.firebase-firebase-encoders-json-17.1.0-third_party_licenses.txt) |
| `com.google.android.odml:image:1.0.0-beta1` | Android Software Development Kit License | [third_party_licenses.json](licenses/bundled/com.google.android.odml-image-1.0.0-beta1-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.android.odml-image-1.0.0-beta1-third_party_licenses.txt) |
| `com.google.firebase:firebase-components:16.1.0` | The Apache Software License, Version 2.0 | [third_party_licenses.json](licenses/bundled/com.google.firebase-firebase-components-16.1.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.firebase-firebase-components-16.1.0-third_party_licenses.txt) |
| `com.google.firebase:firebase-annotations:16.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.mlkit:common:18.11.0` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.mlkit-common-18.11.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.mlkit-common-18.11.0-third_party_licenses.txt) |
| `androidx.appcompat:appcompat:1.6.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.appcompat:appcompat-resources:1.6.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.vectordrawable:vectordrawable:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.vectordrawable:vectordrawable-animated:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.interpolator:interpolator:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.cursoradapter:cursoradapter:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.drawerlayout:drawerlayout:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.emoji2:emoji2:1.2.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-process:2.4.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.startup:startup-runtime:1.1.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.emoji2:emoji2-views-helper:1.2.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.resourceinspection:resourceinspection-annotation:1.0.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.mlkit:vision-common:17.3.0` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.mlkit-vision-common-17.3.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.mlkit-vision-common-17.3.0-third_party_licenses.txt) |
| `androidx.exifinterface:exifinterface:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.mlkit:vision-interfaces:16.3.0` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.mlkit-vision-interfaces-16.3.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.mlkit-vision-interfaces-16.3.0-third_party_licenses.txt) |
| `com.google.mlkit:text-recognition-bundled-common:17.0.0` | ML Kit Terms of Service | [third_party_licenses.json](licenses/bundled/com.google.mlkit-text-recognition-bundled-common-17.0.0-third_party_licenses.json)、[third_party_licenses.txt](licenses/bundled/com.google.mlkit-text-recognition-bundled-common-17.0.0-third_party_licenses.txt) |
| `com.google.dagger:hilt-android:2.51.1` | Apache 2.0 | 未附独立文件 |
| `com.google.dagger:dagger:2.51.1` | Apache 2.0 | 未附独立文件 |
| `com.google.dagger:dagger-lint-aar:2.51.1` | Apache 2.0 | 未附独立文件 |
| `com.google.dagger:hilt-core:2.51.1` | Apache 2.0 | 未附独立文件 |
| `com.google.code.findbugs:jsr305:3.0.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
