# 第三方版权与许可

Dollhouse 自有代码使用 GPL-3.0。下列代码、图标及依赖分别遵循自身许可，项目许可不替代第三方条款。

## 代码与图标

- Lucide：图标路径适配为 Android VectorDrawable；完整 ISC 及 Feather 衍生部分 MIT 文本见 [Lucide-LICENSE](licenses/Lucide-LICENSE.txt)。同时保留之前随项目附带的 [版权文本](licenses/Lucide-legacy-ISC.txt)。
- Shizuku API 13.1.5（api / aidl / shared / provider）：客户端依赖适用 [MIT](licenses/Shizuku-API-MIT.txt)。独立 Shizuku 管理器不随 APK 分发，其许可另行适用。
- Hilt / Dagger、AndroidX、Kotlin / 协程、Guava listenablefuture、javax.inject、JSR305 与部分 Google 开源基础组件：见 [Apache-2.0](licenses/Apache-2.0.txt) 及所附逐组件版权文件。
- Google ML Kit / Play services / ODML：适用 [ML Kit 服务条款](https://developers.google.com/ml-kit/terms) 或 [Android SDK 条款](https://developer.android.com/studio/terms.html)，并非都以开源许可发布。其实际构建 AAR 内附带的第三方许可原文与索引已完整提取至 licenses/bundled/，不得因首页缩短而删除这些文本。

Kotlin 附带的 [LICENSE](licenses/Kotlin-LICENSE.txt) 与 [NOTICE](licenses/Kotlin-NOTICE.txt)、协程 [LICENSE](licenses/Coroutines-LICENSE.txt)、Dagger [LICENSE](licenses/Dagger-LICENSE.txt) 和 Guava [LICENSE](licenses/Guava-LICENSE.txt) 同时保留。构建工具的编译器声明与运行时组件适用范围以对应上游文件为准。

lamda 是运行时按需下载的外部工具，不随此 APK 分发，遵循其自身许可。人偶素材与原桌宠代码由项目所有者确认自有。

## 实际发行构建依赖

> ⚙️ **本节由 `tools/gen_notices.py` 自动生成，请勿手工编辑。** 数据源是本次 release 构建产出的 `app/build/outputs/sdk-dependencies/release/sdkDependencies.txt`（AGP 自动生成，含**全部解析到的传递依赖**）；许可字段取自各组件 POM 的 `<licenses>` 段。
> 
> 口径说明：列入本表的是**解析到的依赖**，不代表 R8 后每个模块的字节码都保留；这比"实际进包的类"更保守（宁多不少）。POM 缺 `<licenses>` 或缓存缺 POM 的条目会**明说原因**，不臆测为 Apache-2.0。
> 
> 修改方式：先跑 `python3 tools/gen_notices.py` 看差异，确认后 `--write`；CI 用 `--check`，不一致即失败。顶部「代码与图标」一节是手写的（Lucide / Shizuku / lamda 不是 maven 制品），脚本不会碰它。

| 组件 | POM 许可/条款 | 内附版权文件 |
| --- | --- | --- |
| `androidx.activity:activity:1.12.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.activity:activity-compose:1.12.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.activity:activity-ktx:1.12.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.annotation:annotation:1.9.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.annotation:annotation-experimental:1.4.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.annotation:annotation-jvm:1.9.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.appcompat:appcompat:1.6.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.appcompat:appcompat-resources:1.6.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.arch.core:core-common:2.2.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.arch.core:core-runtime:2.2.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.autofill:autofill:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.collection:collection:1.5.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.collection:collection-jvm:1.5.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.collection:collection-ktx:1.5.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose:compose-bom:2026.01.01` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.animation:animation:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.animation:animation-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.animation:animation-core:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.animation:animation-core-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.foundation:foundation:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.foundation:foundation-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.foundation:foundation-layout:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.foundation:foundation-layout-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.material:material-ripple:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.material:material-ripple-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.material3:material3:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.material3:material3-android:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-annotation:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-annotation-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-retain:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-retain-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-saveable:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.runtime:runtime-saveable-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-geometry:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-geometry-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-graphics:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-graphics-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-text:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-text-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-tooling-preview:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-tooling-preview-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-unit:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-unit-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-util:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.compose.ui:ui-util-android:1.10.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.concurrent:concurrent-futures:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.core:core:1.16.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.core:core-ktx:1.16.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.core:core-viewtree:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.cursoradapter:cursoradapter:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.customview:customview:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.customview:customview-poolingcontainer:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.documentfile:documentfile:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.drawerlayout:drawerlayout:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.dynamicanimation:dynamicanimation:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.emoji2:emoji2:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.emoji2:emoji2-views-helper:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.exifinterface:exifinterface:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.fragment:fragment:1.5.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.graphics:graphics-path:1.0.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.interpolator:interpolator:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.legacy:legacy-support-core-utils:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-common:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-common-java8:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-common-jvm:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-livedata:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-livedata-core:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-process:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime-android:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime-compose:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.10.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.loader:loader:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigation3:navigation3-runtime:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigation3:navigation3-runtime-android:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigation3:navigation3-ui:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigation3:navigation3-ui-android:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigationevent:navigationevent:1.0.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigationevent:navigationevent-android:1.0.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigationevent:navigationevent-compose:1.0.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.navigationevent:navigationevent-compose-android:1.0.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.print:print:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.profileinstaller:profileinstaller:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.resourceinspection:resourceinspection-annotation:1.0.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.savedstate:savedstate:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.savedstate:savedstate-android:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.savedstate:savedstate-compose:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.savedstate:savedstate-compose-android:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.savedstate:savedstate-ktx:1.4.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.startup:startup-runtime:1.1.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.tracing:tracing:1.2.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.transition:transition:1.6.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.vectordrawable:vectordrawable:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.vectordrawable:vectordrawable-animated:1.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.viewpager:viewpager:1.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.window:window:1.5.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.window:window-core:1.5.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `androidx.window:window-core-android:1.5.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.airbnb.android:lottie:6.7.1` | Apache-2.0 | 未附独立文件 |
| `com.google.android.datatransport:transport-api:2.2.1` | The Apache Software License, Version 2.0 | [com.google.android.datatransport-transport-api-2.2.1-third_party_licenses.json](licenses/bundled/com.google.android.datatransport-transport-api-2.2.1-third_party_licenses.json)、[com.google.android.datatransport-transport-api-2.2.1-third_party_licenses.txt](licenses/bundled/com.google.android.datatransport-transport-api-2.2.1-third_party_licenses.txt) |
| `com.google.android.datatransport:transport-backend-cct:2.3.3` | The Apache Software License, Version 2.0 | [com.google.android.datatransport-transport-backend-cct-2.3.3-third_party_licenses.json](licenses/bundled/com.google.android.datatransport-transport-backend-cct-2.3.3-third_party_licenses.json)、[com.google.android.datatransport-transport-backend-cct-2.3.3-third_party_licenses.txt](licenses/bundled/com.google.android.datatransport-transport-backend-cct-2.3.3-third_party_licenses.txt) |
| `com.google.android.datatransport:transport-runtime:2.2.6` | The Apache Software License, Version 2.0 | [com.google.android.datatransport-transport-runtime-2.2.6-third_party_licenses.json](licenses/bundled/com.google.android.datatransport-transport-runtime-2.2.6-third_party_licenses.json)、[com.google.android.datatransport-transport-runtime-2.2.6-third_party_licenses.txt](licenses/bundled/com.google.android.datatransport-transport-runtime-2.2.6-third_party_licenses.txt) |
| `com.google.android.gms:play-services-base:18.5.0` | Android Software Development Kit License | [com.google.android.gms-play-services-base-18.5.0-third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-base-18.5.0-third_party_licenses.json)、[com.google.android.gms-play-services-base-18.5.0-third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-base-18.5.0-third_party_licenses.txt) |
| `com.google.android.gms:play-services-basement:18.4.0` | Android Software Development Kit License | [com.google.android.gms-play-services-basement-18.4.0-third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-basement-18.4.0-third_party_licenses.json)、[com.google.android.gms-play-services-basement-18.4.0-third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-basement-18.4.0-third_party_licenses.txt) |
| `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1` | ML Kit Terms of Service | [com.google.android.gms-play-services-mlkit-text-recognition-chinese-16.0.1-third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-chinese-16.0.1-third_party_licenses.json)、[com.google.android.gms-play-services-mlkit-text-recognition-chinese-16.0.1-third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-chinese-16.0.1-third_party_licenses.txt) |
| `com.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0` | ML Kit Terms of Service | [com.google.android.gms-play-services-mlkit-text-recognition-common-19.1.0-third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-common-19.1.0-third_party_licenses.json)、[com.google.android.gms-play-services-mlkit-text-recognition-common-19.1.0-third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-mlkit-text-recognition-common-19.1.0-third_party_licenses.txt) |
| `com.google.android.gms:play-services-tasks:18.2.0` | Android Software Development Kit License | [com.google.android.gms-play-services-tasks-18.2.0-third_party_licenses.json](licenses/bundled/com.google.android.gms-play-services-tasks-18.2.0-third_party_licenses.json)、[com.google.android.gms-play-services-tasks-18.2.0-third_party_licenses.txt](licenses/bundled/com.google.android.gms-play-services-tasks-18.2.0-third_party_licenses.txt) |
| `com.google.android.odml:image:1.0.0-beta1` | Android Software Development Kit License | [com.google.android.odml-image-1.0.0-beta1-third_party_licenses.json](licenses/bundled/com.google.android.odml-image-1.0.0-beta1-third_party_licenses.json)、[com.google.android.odml-image-1.0.0-beta1-third_party_licenses.txt](licenses/bundled/com.google.android.odml-image-1.0.0-beta1-third_party_licenses.txt) |
| `com.google.code.findbugs:jsr305:3.0.2` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.dagger:dagger:2.57.2` | Apache 2.0 | 未附独立文件 |
| `com.google.dagger:dagger-lint-aar:2.57.2` | Apache 2.0 | 未附独立文件 |
| `com.google.dagger:hilt-android:2.57.2` | Apache 2.0 | 未附独立文件 |
| `com.google.dagger:hilt-core:2.57.2` | Apache 2.0 | 未附独立文件 |
| `com.google.firebase:firebase-annotations:16.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.firebase:firebase-components:16.1.0` | The Apache Software License, Version 2.0 | [com.google.firebase-firebase-components-16.1.0-third_party_licenses.json](licenses/bundled/com.google.firebase-firebase-components-16.1.0-third_party_licenses.json)、[com.google.firebase-firebase-components-16.1.0-third_party_licenses.txt](licenses/bundled/com.google.firebase-firebase-components-16.1.0-third_party_licenses.txt) |
| `com.google.firebase:firebase-encoders:16.1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.firebase:firebase-encoders-json:17.1.0` | The Apache Software License, Version 2.0 | [com.google.firebase-firebase-encoders-json-17.1.0-third_party_licenses.json](licenses/bundled/com.google.firebase-firebase-encoders-json-17.1.0-third_party_licenses.json)、[com.google.firebase-firebase-encoders-json-17.1.0-third_party_licenses.txt](licenses/bundled/com.google.firebase-firebase-encoders-json-17.1.0-third_party_licenses.txt) |
| `com.google.guava:listenablefuture:1.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `com.google.mlkit:common:18.11.0` | ML Kit Terms of Service | [com.google.mlkit-common-18.11.0-third_party_licenses.json](licenses/bundled/com.google.mlkit-common-18.11.0-third_party_licenses.json)、[com.google.mlkit-common-18.11.0-third_party_licenses.txt](licenses/bundled/com.google.mlkit-common-18.11.0-third_party_licenses.txt) |
| `com.google.mlkit:text-recognition-bundled-common:17.0.0` | ML Kit Terms of Service | [com.google.mlkit-text-recognition-bundled-common-17.0.0-third_party_licenses.json](licenses/bundled/com.google.mlkit-text-recognition-bundled-common-17.0.0-third_party_licenses.json)、[com.google.mlkit-text-recognition-bundled-common-17.0.0-third_party_licenses.txt](licenses/bundled/com.google.mlkit-text-recognition-bundled-common-17.0.0-third_party_licenses.txt) |
| `com.google.mlkit:text-recognition-chinese:16.0.1` | ML Kit Terms of Service | [com.google.mlkit-text-recognition-chinese-16.0.1-third_party_licenses.json](licenses/bundled/com.google.mlkit-text-recognition-chinese-16.0.1-third_party_licenses.json)、[com.google.mlkit-text-recognition-chinese-16.0.1-third_party_licenses.txt](licenses/bundled/com.google.mlkit-text-recognition-chinese-16.0.1-third_party_licenses.txt) |
| `com.google.mlkit:vision-common:17.3.0` | ML Kit Terms of Service | [com.google.mlkit-vision-common-17.3.0-third_party_licenses.json](licenses/bundled/com.google.mlkit-vision-common-17.3.0-third_party_licenses.json)、[com.google.mlkit-vision-common-17.3.0-third_party_licenses.txt](licenses/bundled/com.google.mlkit-vision-common-17.3.0-third_party_licenses.txt) |
| `com.google.mlkit:vision-interfaces:16.3.0` | ML Kit Terms of Service | [com.google.mlkit-vision-interfaces-16.3.0-third_party_licenses.json](licenses/bundled/com.google.mlkit-vision-interfaces-16.3.0-third_party_licenses.json)、[com.google.mlkit-vision-interfaces-16.3.0-third_party_licenses.txt](licenses/bundled/com.google.mlkit-vision-interfaces-16.3.0-third_party_licenses.txt) |
| `com.squareup.okio:okio:1.17.6` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `dev.rikka.shizuku:aidl:13.1.5` | MIT License | 未附独立文件 |
| `dev.rikka.shizuku:api:13.1.5` | MIT License | 未附独立文件 |
| `dev.rikka.shizuku:provider:13.1.5` | MIT License | 未附独立文件 |
| `dev.rikka.shizuku:shared:13.1.5` | MIT License | 未附独立文件 |
| `jakarta.inject:jakarta.inject-api:2.0.1` | The Apache Software License, Version 2.0 | [jakarta.inject-jakarta.inject-api-2.0.1-META-INF-NOTICE.md](licenses/bundled/jakarta.inject-jakarta.inject-api-2.0.1-META-INF-NOTICE.md) |
| `javax.inject:javax.inject:1` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.bouncycastle:bcprov-jdk18on:1.79` | Bouncy Castle Licence | [org.bouncycastle-bcprov-jdk18on-1.79-org-bouncycastle-LICENSE-extracted.txt](licenses/bundled/org.bouncycastle-bcprov-jdk18on-1.79-org-bouncycastle-LICENSE-extracted.txt) |
| `org.jetbrains:annotations:23.0.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.10` | The Apache License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3` | The Apache Software License, Version 2.0 | 未附独立文件 |
| `org.jspecify:jspecify:1.0.0` | The Apache License, Version 2.0 | 未附独立文件 |
