# kotlinx.serialization ships its own R8 rules; keep the generated serializers of our @Serializable
# classes explicitly as well (API DTOs, the plan JSON, attestation, routes), since they are decoded at
# runtime and from files on the device. Maps, ML Kit, Play services and Firebase ship
# their own consumer rules.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class com.arcu.attnd.**$$serializer { *; }
-keepclassmembers class com.arcu.attnd.** {
    *** Companion;
}
-keepclasseswithmembers class com.arcu.attnd.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Crash and ANR stacks (ApplicationExitInfo reports) are retraced on the server with mapping.txt:
# keep line numbers, and name the source file "SourceFile" (retrace maps it back).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
