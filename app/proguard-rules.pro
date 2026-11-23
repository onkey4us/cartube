# Keep MediaServiceCore / Gson / Retrofit models
-keep class com.liskovsoft.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.liskovsoft.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.chromium.net.**
-dontwarn com.eclipsesource.v8.**
