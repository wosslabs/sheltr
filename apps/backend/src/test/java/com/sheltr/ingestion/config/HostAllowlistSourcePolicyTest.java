package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourcePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HostAllowlistSourcePolicyTest {

    private static HostAllowlistSourcePolicy policy(List<String> hosts) {
        return new HostAllowlistSourcePolicy(new IngestionProperties(hosts, null, null));
    }

    @Test
    void allowsAnHttpsUriWhoseHostIsApproved() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://www.ready.gov/x.pdf")).isTrue();
    }

    @Test
    void deniesAHostThatIsNotApproved() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://evil.example/x.pdf")).isFalse();
    }

    @Test
    void deniesAnApprovedHostNameAppearingOnlyInThePath() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://evil.example/www.ready.gov")).isFalse();
    }

    @Test
    void emptyListDeniesEverything() {
        assertThat(policy(List.of()).allows("https://www.ready.gov/x.pdf")).isFalse();
    }

    @Test
    void hostMatchIsExactNotAPrefixOrSuffix() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://www.ready.gov.evil.example/x.pdf")).isFalse();
        assertThat(policy(List.of("www.ready.gov")).allows("https://evil.www.ready.gov/x.pdf")).isFalse();
    }

    @Test
    void hostMatchDeniesATrailingDotOnTheFqdn() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://www.ready.gov./x.pdf")).isFalse();
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        assertThat(policy(List.of("WWW.Ready.GOV")).allows("https://www.READY.gov/x.pdf")).isTrue();
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertThat(policy(List.of("www.ready.gov")).allows("HTTPS://www.ready.gov/x")).isTrue();
    }

    @Test
    void deniesNonHttpsSchemes() {
        HostAllowlistSourcePolicy policy = policy(List.of("www.ready.gov"));
        assertThat(policy.allows("http://www.ready.gov/x.pdf")).isFalse();
        assertThat(policy.allows("file:///etc/passwd")).isFalse();
    }

    @Test
    void allowsTheDefaultHttpsPortExplicitly() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://www.ready.gov:443/x.pdf")).isTrue();
    }

    @Test
    void deniesANonDefaultPort() {
        HostAllowlistSourcePolicy policy = policy(List.of("www.ready.gov"));
        assertThat(policy.allows("https://www.ready.gov:8443/x")).isFalse();
        assertThat(policy.allows("https://www.ready.gov:80/x")).isFalse();
    }

    @Test
    void deniesAUriWithNoHost() {
        assertThat(policy(List.of("www.ready.gov")).allows("https:///x.pdf")).isFalse();
        assertThat(policy(List.of("www.ready.gov")).allows("https:x.pdf")).isFalse();
    }

    @Test
    void deniesUserinfoInTheAuthority() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://user@www.ready.gov/x.pdf")).isFalse();
        assertThat(policy(List.of("www.ready.gov")).allows("https://www.ready.gov@evil.example/x.pdf")).isFalse();
    }

    @Test
    void deniesInvalidUriSyntaxAndNull() {
        HostAllowlistSourcePolicy policy = policy(List.of("www.ready.gov"));
        assertThat(policy.allows("https://www.ready.gov/a b.pdf")).isFalse();
        assertThat(policy.allows(null)).isFalse();
    }

    @Test
    void rejectionMessageForCredentialsIsDistinctAndEchoesNothing() {
        assertThat(SourcePolicy.rejectionMessage("https://user:secret@www.ready.gov/x.pdf"))
                .isEqualTo("Source URI must not contain credentials.");
        assertThat(SourcePolicy.rejectionMessage("https://www.ready.gov@evil.example/x.pdf"))
                .isEqualTo("Source URI must not contain credentials.");
    }

    @Test
    void rejectionMessageNamesTheHostForOtherDenials() {
        assertThat(SourcePolicy.rejectionMessage("https://evil.example/x.pdf"))
                .isEqualTo("Source host not allowed: evil.example");
    }

    @Test
    void rejectionMessageTruncatesAnOverlongHostTo255Characters() {
        String longHost = "a".repeat(1000) + ".example";
        String message = SourcePolicy.rejectionMessage("https://" + longHost + "/x.pdf");
        assertThat(message).isEqualTo("Source host not allowed: " + longHost.substring(0, 255));
        assertThat(message.length()).isEqualTo("Source host not allowed: ".length() + 255);
    }
}
