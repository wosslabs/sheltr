package com.sheltr.ingestion.config;

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
    void emptyListDeniesEverything() {
        assertThat(policy(List.of()).allows("https://www.ready.gov/x.pdf")).isFalse();
    }

    @Test
    void hostMatchIsExactNotAPrefixOrSuffix() {
        assertThat(policy(List.of("www.ready.gov")).allows("https://www.ready.gov.evil.example/x.pdf")).isFalse();
        assertThat(policy(List.of("www.ready.gov")).allows("https://evil.www.ready.gov/x.pdf")).isFalse();
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        assertThat(policy(List.of("WWW.Ready.GOV")).allows("https://www.READY.gov/x.pdf")).isTrue();
    }

    @Test
    void deniesNonHttpsSchemes() {
        HostAllowlistSourcePolicy policy = policy(List.of("www.ready.gov"));
        assertThat(policy.allows("http://www.ready.gov/x.pdf")).isFalse();
        assertThat(policy.allows("file:///etc/passwd")).isFalse();
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
}
