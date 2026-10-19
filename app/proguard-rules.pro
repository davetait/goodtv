# kotlinx.serialization: keep generated serializers and companions for our @Serializable classes
# (SourceConfig stored as JSON, Navigation 3 keys saved in instance state).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.keyspaceapps.goodtv.**$$serializer { *; }
-keepclassmembers class com.keyspaceapps.goodtv.** { *** Companion; }
-keepclasseswithmembers class com.keyspaceapps.goodtv.** { kotlinx.serialization.KSerializer serializer(...); }
# Navigation 3 restores back-stack keys by class; keep them intact.
-keep class com.keyspaceapps.goodtv.ui.nav.** { *; }
