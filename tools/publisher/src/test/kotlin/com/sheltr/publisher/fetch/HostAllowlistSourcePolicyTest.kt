package com.sheltr.publisher.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostAllowlistSourcePolicyTest {

    private fun policy(vararg hosts: String) = HostAllowlistSourcePolicy(hosts.toList())

    @Test
    fun allowsAnHttpsUriWhoseHostIsApproved() {
        assertTrue(policy("www.ready.gov").allows("https://www.ready.gov/x.pdf"))
    }

    @Test
    fun deniesAHostThatIsNotApproved() {
        assertFalse(policy("www.ready.gov").allows("https://evil.example/x.pdf"))
    }

    @Test
    fun deniesAnApprovedHostNameAppearingOnlyInThePath() {
        assertFalse(policy("www.ready.gov").allows("https://evil.example/www.ready.gov"))
    }

    @Test
    fun emptyListDeniesEverything() {
        assertFalse(policy().allows("https://www.ready.gov/x.pdf"))
    }

    @Test
    fun hostMatchIsExactNotAPrefixOrSuffix() {
        assertFalse(policy("www.ready.gov").allows("https://www.ready.gov.evil.example/x.pdf"))
        assertFalse(policy("www.ready.gov").allows("https://evil.www.ready.gov/x.pdf"))
    }

    @Test
    fun hostMatchDeniesATrailingDotOnTheFqdn() {
        assertFalse(policy("www.ready.gov").allows("https://www.ready.gov./x.pdf"))
    }

    @Test
    fun hostMatchIsCaseInsensitive() {
        assertTrue(policy("WWW.Ready.GOV").allows("https://www.READY.gov/x.pdf"))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertTrue(policy("www.ready.gov").allows("HTTPS://www.ready.gov/x"))
    }

    @Test
    fun deniesNonHttpsSchemes() {
        val policy = policy("www.ready.gov")
        assertFalse(policy.allows("http://www.ready.gov/x.pdf"))
        assertFalse(policy.allows("file:///etc/passwd"))
    }

    @Test
    fun allowsTheDefaultHttpsPortExplicitly() {
        assertTrue(policy("www.ready.gov").allows("https://www.ready.gov:443/x.pdf"))
    }

    @Test
    fun deniesANonDefaultPort() {
        val policy = policy("www.ready.gov")
        assertFalse(policy.allows("https://www.ready.gov:8443/x"))
        assertFalse(policy.allows("https://www.ready.gov:80/x"))
    }

    @Test
    fun deniesAUriWithNoHost() {
        assertFalse(policy("www.ready.gov").allows("https:///x.pdf"))
        assertFalse(policy("www.ready.gov").allows("https:x.pdf"))
    }

    @Test
    fun deniesUserinfoInTheAuthority() {
        assertFalse(policy("www.ready.gov").allows("https://user@www.ready.gov/x.pdf"))
        assertFalse(policy("www.ready.gov").allows("https://www.ready.gov@evil.example/x.pdf"))
    }

    @Test
    fun deniesInvalidUriSyntaxAndNull() {
        val policy = policy("www.ready.gov")
        assertFalse(policy.allows("https://www.ready.gov/a b.pdf"))
        assertFalse(policy.allows(null))
    }

    @Test
    fun rejectionMessageForCredentialsIsDistinctAndEchoesNothing() {
        assertEquals(
            "Source URI must not contain credentials.",
            SourcePolicy.rejectionMessage("https://user:secret@www.ready.gov/x.pdf"),
        )
        assertEquals(
            "Source URI must not contain credentials.",
            SourcePolicy.rejectionMessage("https://www.ready.gov@evil.example/x.pdf"),
        )
    }

    @Test
    fun rejectionMessageNamesTheHostForOtherDenials() {
        assertEquals(
            "Source host not allowed: evil.example",
            SourcePolicy.rejectionMessage("https://evil.example/x.pdf"),
        )
    }

    @Test
    fun rejectionMessageTruncatesAnOverlongHostTo255Characters() {
        val longHost = "a".repeat(1000) + ".example"
        val message = SourcePolicy.rejectionMessage("https://$longHost/x.pdf")
        assertEquals("Source host not allowed: " + longHost.substring(0, 255), message)
        assertEquals("Source host not allowed: ".length + 255, message.length)
    }
}
