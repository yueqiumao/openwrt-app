# OkHttp / kotlinx.serialization
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jetbrains.annotations.**

# kotlinx.serialization keeps generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ax6.luyouqi.**$$serializer { *; }
-keepclassmembers class com.ax6.luyouqi.** {
    *** Companion;
}

# Room 生成的实现类与实体
-keep class com.ax6.luyouqi.data.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.**
