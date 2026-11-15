package net.atlasauth.atlas

/** What a step-up factor needs from the person. A factor the SDK does not know yet maps to [OTP]. */
enum class StepUpInputKind { PASSWORD, OTP, NONE, REDIRECT, PUSH, WEBAUTHN }

/** How to render a step-up factor. [factor] is the raw wire string. */
data class StepUpFactorInfo(val factor: String, val label: String, val needsInput: Boolean, val inputKind: StepUpInputKind)

private val known = mapOf(
    "password" to Triple("Password", true, StepUpInputKind.PASSWORD),
    "passkey" to Triple("Passkey", false, StepUpInputKind.WEBAUTHN),
    "totp" to Triple("Authenticator app code", true, StepUpInputKind.OTP),
    "backup_code" to Triple("Backup code", true, StepUpInputKind.OTP),
    "email_code" to Triple("Code sent by email", true, StepUpInputKind.OTP),
    "phone_code" to Triple("Code sent by text message", true, StepUpInputKind.OTP),
    "push" to Triple("Approve on your device", false, StepUpInputKind.PUSH),
    "duo" to Triple("Duo", false, StepUpInputKind.REDIRECT),
    "yubikey" to Triple("Security key code", true, StepUpInputKind.OTP),
)

/** Every factor this SDK version knows; a newer server may offer more. */
val STEP_UP_FACTORS: List<String> = known.keys.toList()

/** Render info for any factor string; an unknown one gets a readable label and a typed-code input. */
fun stepUpFactorInfo(factor: String): StepUpFactorInfo {
    known[factor]?.let { return StepUpFactorInfo(factor, it.first, it.second, it.third) }
    val words = factor.replace(Regex("[_-]+"), " ").trim()
    val label = if (words.isEmpty()) "Verification" else words.replaceFirstChar { it.uppercaseChar() }
    return StepUpFactorInfo(factor, label, true, StepUpInputKind.OTP)
}
