# kotlinx.serialization: keep generated serializers for rule models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.privateinbox.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
