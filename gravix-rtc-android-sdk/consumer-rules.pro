# Kotlin Serialization Proguard Rules
########################################

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.gravixcloud.rtc.**$$serializer { *; }

-keepclasseswithmembers class com.gravixcloud.rtc.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebRTC
#########################################
# Ensure JNI native method names and descriptors are preserved (Java -> native).
-keepclasseswithmembernames,includedescriptorclasses class org.webrtc.** {
    native <methods>;
}

# Ensure java methods called from Native are preserved (native -> Java).
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.**.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.**.CalledByNativeUnchecked <methods>;
}

# NIST sdp parser
#########################################
# Preserve reflection used for Parser registrations
-keep class android.gov.nist.javax.sdp.parser.*Parser { *; }
-keep class android.gov.nist.javax.sdp.parser.ParserFactory { *; }
-keep class android.gov.nist.javax.sdp.parser.SDPParser { *; }

# Protobuf
#########################################
-keepclassmembers class gravixcloud.** extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}
-keepclassmembers class com.google.protobuf.** extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# JNA (UniFFI data track bindings)
#########################################
# libjnidispatch looks up JNA's classes and fields by name, and JNA reads the bindings'
# Structure and Callback subclasses reflectively. gravix-uniffi-android ships no rules for
# either. These are the rules from JNA's FAQ.
-dontwarn java.awt.*
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# audioswitch (vendored, third_party/audioswitch): its own consumer rules, relocated
#########################################
-keep class com.gravixcloud.rtc.audioswitch.** { *; }
-keepattributes InnerClasses
