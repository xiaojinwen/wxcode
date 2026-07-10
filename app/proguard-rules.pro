# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class leng.hook.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**