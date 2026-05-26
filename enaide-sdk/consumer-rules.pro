# Regole ProGuard/R8 consumate dai progetti che integrano enaide-sdk.
# Manteniamo intatti i nomi dei symbol pubblici dell'SDK.

-keep public class com.enaide.sdk.** { public *; }

# Modelli serializzati con kotlinx.serialization: il plugin genera i serializer
# come oggetti companion / classi $$ — vanno preservati.
-keepclasseswithmembers class com.enaide.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.enaide.sdk.**$$serializer { *; }
