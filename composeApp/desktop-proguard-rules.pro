-dontoptimize
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn dev.chrisbanes.haze.**
-dontwarn io.github.alexzhirkevich.compottie.**
-dontwarn androidx.compose.ui.graphics.**
-dontwarn org.jetbrains.skia.**
-dontwarn javafx.**
-dontwarn org.eclipse.swt.**
-dontwarn com.sun.javafx.**

-keep class com.nuvio.app.** { *; }
-keep interface com.nuvio.app.** { *; }
-keep enum com.nuvio.app.** { *; }

-keep class coil3.** { *; }
-keep interface coil3.** { *; }
-keep enum coil3.** { *; }

-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep enum io.ktor.** { *; }

-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-keep enum kotlinx.serialization.** { *; }

-keep class dev.whyoleg.** { *; }
-keep interface dev.whyoleg.** { *; }
-keep enum dev.whyoleg.** { *; }

-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-keep enum kotlinx.coroutines.** { *; }

-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep enum com.sun.jna.** { *; }

-keep class com.typesafe.config.** { *; }
-keep interface com.typesafe.config.** { *; }
-keep enum com.typesafe.config.** { *; }

-keep class io.ktor.client.engine.java.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class coil3.network.ktor3.internal.** { *; }
-keep class dev.whyoleg.cryptography.providers.jdk.** { *; }
-keep class io.ktor.server.config.** { *; }

-keep class org.jetbrains.skiko.SkiaLayer { *; }
-keep class org.jetbrains.skiko.renderer.** { *; }
