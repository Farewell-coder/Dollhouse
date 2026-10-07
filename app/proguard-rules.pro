# ============================================================================
# Dollhouse release 混淆 / 收缩规则
#
# 【为何需要】release 开启了 minifyEnabled + shrinkResources：
#   - minify 去掉未引用代码与全部 Logs.* 调用（Logs.ON 是编译期常量 false，
#     R8 会把方法体清空并进一步内联删掉，达到「release 不打日志」的目的）；
#   - shrink 去掉未被引用的资源，配合本轮删掉的零引用位图进一步瘦身。
#
# 【必须保留的部分】本工程只有一处真正的反射调用面：ShizukuBridge 通过
#   Class.forName("rikka.shizuku.Shizuku") + getDeclaredMethod("newProcess")
#   调起 shell，再 getMethod("waitForTimeout") 等远程进程超时。R8 看不到这些
#   字符串背后的类与方法，若不显式 keep，混淆后必然 NoSuchMethodException。
# ============================================================================

# ---- Shizuku：AIDL 接口 + 远程进程实现全靠反射/跨进程契约，整包保留 ----
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Shizuku aar 里带的是编译期注解（androidx.annotation），非运行时依赖。
# 本工程 android.useAndroidX=false，注解类不在类路径上，不静默会报 missing class。
-dontwarn androidx.annotation.**

# ---- MLKit OCR：靠 Manifest meta-data + 反射加载 Registrar，模型解析走 JNI ----
# 【为何必须整包 keep】文字识别组件不是直接 new 出来的，而是先由
#   MlKitComponentDiscoveryService 读 Manifest 里的 meta-data，再反射加载各 Registrar，
#   最后反射查服务实现；R8 看不到这些字符串背后的类，裁掉后运行时会报
#   「MlKitException: Failed to load text recognizer」这类只在真机才暴露的错。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text**

# ---- 反射调用的兜底：保留签名 / 注解 / 内部类信息 ----
# getDeclaredMethod / getMethod 依赖参数类型与返回类型签名，
# 匿名类与内部类被改名后 try/catch 的 getSimpleName() 会失真，一并保留属性。
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
-keepattributes *Annotation*

# ---- Parcelable：CREATOR 字段名是系统按字面量反射查找的 ----
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---- 枚举：values()/valueOf() 由编译器生成，混淆后可能被裁掉 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 组件类：Manifest 已声明的由 AGP 自动 keep，这里只兜底 WebView/JS 桥 ----
# （本工程暂未使用 WebView，留作后续扩展的安全垫。）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 行号：崩溃栈里保留可定位到源码的行号，代价仅几十 KB ----
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
