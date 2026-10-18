# Keep the services and activities declared in the manifest (R8 does this automatically),
# plus the zxing scanner classes it starts by name.
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
