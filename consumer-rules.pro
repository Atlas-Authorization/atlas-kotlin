# Consumer ProGuard/R8 rules shipped to apps that depend on the Atlas SDK.
#
# kotlinx.serialization generates synthetic `$serializer` companions and a
# `Companion` object per @Serializable class; R8 must keep them or decoding the
# FAPI models fails at runtime with a SerializationException.

# Keep the generated serializers for every Atlas model.
-keepclassmembers class net.atlasauth.atlas.** {
    *** Companion;
}
-keepclasseswithmembers class net.atlasauth.atlas.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.atlasauth.atlas.**$$serializer { *; }

# OkHttp ships its own rules, but these silence R8 warnings for its optional deps.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
