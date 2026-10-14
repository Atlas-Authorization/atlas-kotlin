package net.atlasauth.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the status → [FlowStep] mapping — the client half of the
 * §5 "the server picks the step" contract. No server, no device.
 */
class FlowStepTest {

    private fun attempt(status: String, strategies: List<String>? = null, session: String? = null) =
        FlowAttempt(id = "sia_1", status = status, supportedFirstFactors = strategies, createdSessionId = session)

    @Test
    fun mapsEveryKnownStatus() {
        assertEquals(FlowStep.CollectIdentifier, nextStep(attempt("needs_identifier")))
        assertEquals(
            FlowStep.CollectFirstFactor(listOf("password", "email_code")),
            nextStep(attempt("needs_first_factor", strategies = listOf("password", "email_code"))),
        )
        assertEquals(FlowStep.CollectSecondFactor, nextStep(attempt("needs_second_factor")))
        assertEquals(FlowStep.EnrollSecondFactor, nextStep(attempt("needs_mfa_enrollment")))
        assertEquals(FlowStep.CollectEmailCode, nextStep(attempt("needs_email_verification")))
        assertEquals(FlowStep.CollectCaptcha, nextStep(attempt("needs_captcha")))
        assertEquals(FlowStep.AwaitOauthCallback, nextStep(attempt("needs_oauth_callback")))
        assertEquals(FlowStep.CollectNewPassword, nextStep(attempt("needs_new_password")))
        assertEquals(FlowStep.Done("sess_9"), nextStep(attempt("complete", session = "sess_9")))
        assertEquals(FlowStep.Restart("abandoned"), nextStep(attempt("abandoned")))
    }

    @Test
    fun firstFactorStrategiesDefaultToEmptyNeverNull() {
        val step = nextStep(attempt("needs_first_factor", strategies = null))
        assertEquals(FlowStep.CollectFirstFactor(emptyList()), step)
    }

    @Test
    fun unknownStatusMapsToUnknownNotABlankScreen() {
        val step = nextStep(attempt("needs_quantum_handshake"))
        assertTrue(step is FlowStep.Unknown)
        assertEquals("needs_quantum_handshake", (step as FlowStep.Unknown).status)
    }

    @Test
    fun terminalStatusesAreCompleteAndAbandoned() {
        assertTrue(isTerminal(attempt("complete")))
        assertTrue(isTerminal(attempt("abandoned")))
        assertFalse(isTerminal(attempt("needs_first_factor")))
    }
}
