# kotlinx.serialization: keep generated serializers (the library ships most rules; these cover
# classes reached only through serializer() lookups).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.hotspotmonitor.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hotspotmonitor.**$$serializer { *; }
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
