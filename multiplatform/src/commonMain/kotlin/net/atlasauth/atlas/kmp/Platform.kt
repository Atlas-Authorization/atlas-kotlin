package net.atlasauth.atlas.kmp

/** Cryptographically secure random bytes from the platform (never `kotlin.random`). */
internal expect fun secureRandomBytes(size: Int): ByteArray
