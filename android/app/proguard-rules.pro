# ProGuard rules for FitrahTube

# Keep data classes and DTOs
-keep class com.albunyaan.tube.data.model.** { *; }
-keep class com.albunyaan.tube.data.dto.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# NewPipe Extractor
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# re2j (used by jsoup in NewPipeExtractor dev-SNAPSHOT)
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

# Rhino JavaScript (used by NewPipe)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**

# AndroidX Media3 (replaces ExoPlayer2)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# DataStore
-keep class androidx.datastore.*.** { *; }

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# AndroidX Navigation — NavType subclasses are looked up by name when restoring
# saved-state bundles.
-keep class androidx.navigation.** { *; }
-keep class * extends androidx.navigation.NavType { *; }
-keepnames class * extends androidx.navigation.NavArgs

# Moshi — keep generated *JsonAdapter classes and data classes annotated with
# @JsonClass, plus Kotlin reflection metadata so KotlinJsonAdapterFactory's
# fallback path doesn't mis-classify data classes as "abstract".
-keep,allowobfuscation,allowshrinking @interface com.squareup.moshi.JsonClass
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **.*JsonAdapter { *; }
-keepclassmembers class * extends com.squareup.moshi.JsonAdapter {
    <init>(...);
}
-keepclassmembers,allowshrinking,allowobfuscation @com.squareup.moshi.JsonClass class * {
    <fields>;
    <init>(...);
}

# Kotlin reflect metadata used by Moshi's KotlinJsonAdapterFactory.
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,InnerClasses,EnclosingMethod,Signature,Exceptions

# Coroutines — DebugProbesKt referenced by reflection.
-dontwarn kotlinx.coroutines.debug.AgentPremain

# util/MenuIconExt.kt reflects on appcompat internals to force PopupMenu and
# Toolbar overflow menus to render item icons. R8 was renaming the field /
# method names so the reflection silently failed in release — icons looked
# missing in every kebab. Keep the exact symbols looked up.
-keepclassmembernames class androidx.appcompat.widget.PopupMenu {
    androidx.appcompat.view.menu.MenuPopupHelper mPopup;
}
-keepclassmembers class androidx.appcompat.view.menu.MenuPopupHelper {
    public void setForceShowIcon(boolean);
}
-keepclassmembers class androidx.appcompat.view.menu.MenuBuilder {
    public void setOptionalIconsVisible(boolean);
}

# Parcelable / Serializable nav args — keep CREATOR + serialVersionUID so the
# framework can rehydrate fragment arguments after process death.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep enum values used by SafeArgs / NavType.EnumType
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# App's own model classes referenced by nav args (Parcelable + Bundle)
-keep class com.albunyaan.tube.data.** { *; }
-keep class com.albunyaan.tube.player.** { *; }
