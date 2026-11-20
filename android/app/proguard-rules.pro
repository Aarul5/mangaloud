# Rules for release builds (code shrinking with R8).

# The Claude API client reads and writes its classes with Jackson, by reflection, including
# Kotlin metadata for constructors and default values.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisible*Annotations

# Optional parts of those libraries that Android does not have.
-dontwarn com.fasterxml.jackson.**
-dontwarn kotlin.reflect.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.**
-dontwarn com.github.victools.jsonschema.**
# Newer reflection types the client only uses for structured outputs, which the app does not use.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType

# Keep line numbers so crash reports point at the right place.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
