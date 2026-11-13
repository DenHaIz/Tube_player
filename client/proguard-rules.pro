# NewPipeExtractor: Rhino выполняет JS плеера YouTube через рефлексию, шаблоны «N дней назад» грузятся по имени.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# Переведённое название видео берётся из ответа `next` экстрактора (Repository.localizedTitle) — по имени поля.
-keepclassmembers class org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor { com.grack.nanojson.JsonObject nextResponse; }
-keep class org.ocpsoft.prettytime.i18n.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
