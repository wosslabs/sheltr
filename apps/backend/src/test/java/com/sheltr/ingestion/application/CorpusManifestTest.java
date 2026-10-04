package com.sheltr.ingestion.application;

import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusManifestTest {

    private static final String VALID = """
            documents:
              - source_uri: https://example.org/flood.pdf
                title: Flood Guide
                source_agency: Example Agency
                hazards: [FLOOD, LANDSLIDE]
                published_date: 2021-03-15
                license: Example licence, https://example.org/terms
                checksum: abc123
              - source_uri: https://example.org/quake.pdf
                title: Earthquake Guide
                source_agency: Example Agency
                hazards: [EARTHQUAKE]
                published_date: "2019-11-02"
                license: Example licence, https://example.org/terms
                checksum: def456
            related_hazards:
              - hazard: EARTHQUAKE
                related_hazard: LANDSLIDE
                source_uri: https://example.org/quake.pdf
                note: Stated on page 4
            """;

    @Test
    void parsesDocumentsWithBothQuotedAndUnquotedDates() {
        CorpusManifest manifest = parse(VALID);

        assertThat(manifest.documents()).hasSize(2);
        CorpusManifest.Document flood = manifest.documents().get(0);
        assertThat(flood.sourceUri()).isEqualTo("https://example.org/flood.pdf");
        assertThat(flood.checksum()).isEqualTo("abc123");
        assertThat(flood.metadata().disasterTypes())
                .containsExactlyInAnyOrder(DisasterType.FLOOD, DisasterType.LANDSLIDE);
        assertThat(flood.metadata().publishedDate()).isEqualTo(LocalDate.of(2021, 3, 15));
        assertThat(manifest.documents().get(1).metadata().publishedDate()).isEqualTo(LocalDate.of(2019, 11, 2));
    }

    @Test
    void parsesRelatedHazards() {
        CorpusManifest.RelatedHazard related = parse(VALID).relatedHazards().get(0);

        assertThat(related.hazard()).isEqualTo(DisasterType.EARTHQUAKE);
        assertThat(related.relatedHazard()).isEqualTo(DisasterType.LANDSLIDE);
        assertThat(related.sourceUri()).isEqualTo("https://example.org/quake.pdf");
    }

    @Test
    void acceptsAnEmptyManifest() {
        CorpusManifest manifest = parse("documents: []\nrelated_hazards: []\n");

        assertThat(manifest.documents()).isEmpty();
        assertThat(manifest.relatedHazards()).isEmpty();
    }

    @Test
    void rejectsADuplicateSourceUriAndNamesIt() {
        String duplicated = VALID.replace("https://example.org/quake.pdf\n    title",
                "https://example.org/flood.pdf\n    title");

        assertThatThrownBy(() -> parse(duplicated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate source_uri")
                .hasMessageContaining("https://example.org/flood.pdf");
    }

    @Test
    void rejectsAnUnknownHazardAndNamesTheEntry() {
        assertThatThrownBy(() -> parse(VALID.replace("[EARTHQUAKE]", "[METEOR]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("METEOR")
                .hasMessageContaining("https://example.org/quake.pdf");
    }

    @Test
    void rejectsAnEntryWithoutALicence() {
        String withoutLicence = VALID.replaceFirst(
                "    license: Example licence, https://example.org/terms\n", "");

        assertThatThrownBy(() -> parse(withoutLicence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("license")
                .hasMessageContaining("https://example.org/flood.pdf");
    }

    @Test
    void rejectsARelatedHazardWhoseSourceIsNotInTheManifest() {
        String dangling = VALID.replace(
                "    source_uri: https://example.org/quake.pdf\n    note", "    source_uri: https://example.org/missing.pdf\n    note");

        assertThatThrownBy(() -> parse(dangling))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://example.org/missing.pdf");
    }

    private static CorpusManifest parse(String yaml) {
        return CorpusManifest.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
