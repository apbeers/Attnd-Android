# kotlinx.serialization ships its own R8 rules; keep the generated serializers of our DTOs
# explicitly as well, since the bundle JSON is decoded at runtime (and from the disk cache).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class com.arcu.attnd.data.**$$serializer { *; }
-keepclassmembers class com.arcu.attnd.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.arcu.attnd.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
