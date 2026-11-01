-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class com.voicesearch.model.** { *; }
-keep interface com.voicesearch.provider.SearchProvider { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keep class com.voicesearch.service.AssistantService { *; }

# SpeechRecognizer callback — prevent R8 from stripping/renaming
-keep class * implements android.speech.RecognitionListener { *; }

# Moshi DTO classes (reflection-based, R8 must keep them)
-keep class com.voicesearch.provider.TmdbMultiSearchResponse { *; }
-keep class com.voicesearch.provider.TmdbResultItem { *; }
-keep class com.voicesearch.provider.TmdbGenreListResponse { *; }
-keep class com.voicesearch.provider.TmdbGenre { *; }
-keep class com.voicesearch.provider.TmdbException { *; }
-keep class com.voicesearch.provider.TmdbException$* { *; }
-keep class com.voicesearch.provider.TmdbMapper { *; }
# Все DTO TMDB (TmdbDto.kt) разом: поштучный список забыли дополнить при новых
# классах деталей — release упал на открытии карточки, debug-тесты этого не ловят.
-keep class com.voicesearch.provider.Tmdb* { *; }

# Поисковый запрос пользователя не должен попадать в logcat release-сборки.
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
}
