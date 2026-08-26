# BouncyCastle：Ed25519 设备签名依赖其 provider 内部类（部分经 ServiceLoader/反射加载）
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# OkHttp 平台探测用反射
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
