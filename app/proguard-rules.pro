# kotlinx.serialization: keep generated serializers for rule models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.privateinbox.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.privateinbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher: JNI looks these up by name.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Strip SafeLog debug/verbose calls entirely in release (belt and braces on top of Redactor).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
