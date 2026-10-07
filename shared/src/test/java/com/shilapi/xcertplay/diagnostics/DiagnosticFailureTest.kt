package com.shilapi.xcertplay.diagnostics
import org.junit.Assert.*
import org.junit.Test
class DiagnosticFailureTest {
    @Test fun nativeErrnoAndMissingInterfacesRemainUseful() {
        assertEquals("bind/listen failed (errno=98)", DiagnosticFailure.safeReason("bind/listen failed (errno=98)"))
        assertEquals("iPhone configuration does not expose an NCM function", DiagnosticFailure.safeReason("iPhone configuration does not expose an NCM function"))
    }
    @Test fun secretsAndIdentifiersDoNotReachPageOrReport() {
        for (message in listOf("password=super-secret", "certificate 012345", "pairRecord=secret", "failed\npayload=secret")) {
            assertFalse(DiagnosticFailure.safeReason(message).contains("secret"))
        }
        val reason = DiagnosticFailure.safeReason("peer 02:00:00:00:00:01 fe80::1 192.168.1.2 unavailable")
        assertFalse(reason.contains("fe80")); assertFalse(reason.contains("192.168")); assertFalse(reason.contains("02:00"))
    }
}
