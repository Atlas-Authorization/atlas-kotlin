package net.atlasauth.atlas

import org.junit.Assert.assertEquals
import org.junit.Test

class StepUpFactorInfoTest {
    @Test fun knownFactors() {
        assertEquals(9, STEP_UP_FACTORS.size)
        assertEquals(StepUpInputKind.PASSWORD, stepUpFactorInfo("password").inputKind)
        assertEquals(StepUpFactorInfo("passkey", "Passkey", false, StepUpInputKind.WEBAUTHN), stepUpFactorInfo("passkey"))
        assertEquals(StepUpInputKind.REDIRECT, stepUpFactorInfo("duo").inputKind)
        assertEquals(StepUpInputKind.PUSH, stepUpFactorInfo("push").inputKind)
    }

    @Test fun unknownFactorFallsBack() {
        assertEquals(StepUpFactorInfo("smart_card", "Smart card", true, StepUpInputKind.OTP), stepUpFactorInfo("smart_card"))
    }
}
