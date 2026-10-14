# LatvianTube ProGuard rules.
#
# OkHttp ships its own consumer rules; nothing extra is required while
# minifyEnabled is false. Kept as a hook for when it is switched on: reflection
# is used only for org.json-free manual parsing, which needs no keep rules.

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# NewPipe's signature interpreter uses reflection. Keep these if shrinking is enabled.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
