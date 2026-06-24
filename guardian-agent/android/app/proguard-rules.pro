# Add project-specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Keep GuardianAI classes
-keep class com.guardianai.agent.** { *; }

# Supabase
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.guardianai.agent.**$$serializer { *; }
-keepclassmembers class com.guardianai.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.guardianai.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google Play Services
-keep class com.google.android.gms.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
