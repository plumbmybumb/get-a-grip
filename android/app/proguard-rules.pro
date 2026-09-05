# R8 for the release build. `proguard-android-optimize.txt` (AGP's own) is the base; this
# file is only what R8 CANNOT see from the bytecode.
#
# The guiding fact: **`:engine` is reflection-free by construction.** It is a pure Kotlin/JVM
# library whose one JSON door (`BlobCodec`) is hand-written over kotlinx-serialization's
# `JsonElement` tree rather than over `@Serializable` classes, so there is no generated
# serializer to keep and no class R8 has to be told about by name. That is why this file is
# short: everything below is about the two libraries that DO use reflection or codegen —
# Room and kotlinx-serialization's runtime — plus Nordic's BLE stack.
#
# What that also means: if a future change introduces an `@Serializable` data class, its
# serializer is generated code that R8 can see and keep on its own — but the KEEP rules for
# kotlinx-serialization below have to stay, because they are what makes the lookup work.

# ---------------------------------------------------------------- kotlinx-serialization
#
# The library resolves serializers through the synthetic `Companion.serializer()` and the
# `$$serializer` object. R8 cannot see either call site (it goes through reflection in
# `SerializersKt`), so both have to be kept for any class that has them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class run.nuri.getagrip.**$$serializer { *; }
-keepclassmembers class run.nuri.getagrip.** {
    *** Companion;
}
-keepclasseswithmembers class run.nuri.getagrip.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------------------ Room
#
# Room GENERATES its implementations (`GetAGripDatabase_Impl`, the DAO impls) and looks them
# up BY NAME at runtime — `Room.databaseBuilder` reflects on `<Database>_Impl`. A renamed
# class is a `RuntimeException: cannot find implementation` on the first query, which is
# every launch. The entities are named in generated SQL and in the schema JSON, so their
# field names are load-bearing too.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep class run.nuri.getagrip.data.** { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------------------ Nordic
#
# The Android BLE Library reflects on `BleManager`'s callback methods and on the annotated
# request classes; its own consumer rules cover most of it, and this pins the app's managers
# so a stripped callback cannot silently stop a stream.
-keep class no.nordicsemi.android.ble.** { *; }
-dontwarn no.nordicsemi.android.ble.**
-keep class run.nuri.getagrip.ble.** { *; }

# --------------------------------------------------------------------- Play code scanner
#
# The scanner module is downloaded on demand and its entry points are resolved dynamically.
# Play services ship consumer rules; this silences the warnings its optional transitives
# (datatransport, and the `javax.annotation` bits it compiles against) produce.
-dontwarn com.google.android.gms.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**

# -------------------------------------------------------------------------- Diagnostics
#
# Line numbers, and the source-file name rewritten to a constant: a crash report from a
# tester has to name a line, and the original file names are not worth shipping.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ML Kit code scanner (play-services-code-scanner). MEASURED, not guessed (2026-09-04): the
# first R8 release build crashed at Today's first composition with an NPE inside
# com.google.android.gms.internal.mlkit_code_scanner.zzny.<init> — the library reaches its
# own classes reflectively and R8's full mode stripped what it needed. Its consumer rules do
# not cover the scanner's internals, so they are kept here wholesale; the cost is ~100 KB.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**
