# ML Kit / vision 相关类通过反射加载，保留其结构。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }

# 应用自己的数据模型会被 JSON 序列化，字段名不能混淆。
-keepclassmembers class io.github.shimo.smartclickocr.model.** { *; }
-keepclassmembers class io.github.shimo.smartclickocr.data.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
