package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.knowledge.domain.DisasterType;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The approved corpus, as written in corpus/manifest.yaml. */
public record CorpusManifest(List<Document> documents, List<RelatedHazard> relatedHazards) {

    public record Document(String sourceUri, SourceMetadata metadata, String checksum) {
    }

    public record RelatedHazard(DisasterType hazard, DisasterType relatedHazard, String sourceUri, String note) {
    }

    public CorpusManifest {
        documents = List.copyOf(documents);
        relatedHazards = List.copyOf(relatedHazards);
    }

    public static CorpusManifest parse(InputStream in) {
        Object loaded = new Yaml().load(in);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Manifest must be a YAML mapping with a 'documents' list");
        }

        List<Document> documents = new ArrayList<>();
        Set<String> uris = new HashSet<>();
        for (Map<?, ?> entry : mappings(root.get("documents"), "documents")) {
            String uri = text(entry, "source_uri", "(entry without source_uri)");
            if (!uris.add(uri)) {
                throw new IllegalArgumentException("Duplicate source_uri in manifest: " + uri);
            }
            SourceMetadata metadata;
            try {
                metadata = new SourceMetadata(text(entry, "title", uri), text(entry, "source_agency", uri),
                        hazards(entry.get("hazards"), uri), date(entry.get("published_date"), uri),
                        text(entry, "license", uri));
            } catch (IllegalArgumentException e) {
                throw e.getMessage().contains(uri) ? e
                        : new IllegalArgumentException("Manifest entry " + uri + ": " + e.getMessage(), e);
            }
            documents.add(new Document(uri, metadata, text(entry, "checksum", uri)));
        }

        List<RelatedHazard> relatedHazards = new ArrayList<>();
        for (Map<?, ?> entry : mappings(root.get("related_hazards"), "related_hazards")) {
            String uri = text(entry, "source_uri", "(related hazard without source_uri)");
            if (!uris.contains(uri)) {
                throw new IllegalArgumentException(
                        "Related hazard cites " + uri + ", which is not a document in the manifest");
            }
            Object note = entry.get("note");
            relatedHazards.add(new RelatedHazard(hazard(text(entry, "hazard", uri), uri),
                    hazard(text(entry, "related_hazard", uri), uri), uri, note == null ? null : note.toString()));
        }
        return new CorpusManifest(documents, relatedHazards);
    }

    private static List<Map<?, ?>> mappings(Object value, String key) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + key + "' must be a list");
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Every item under '" + key + "' must be a mapping");
            }
            result.add(map);
        }
        return result;
    }

    private static String text(Map<?, ?> entry, String key, String uri) {
        Object value = entry.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Manifest entry " + uri + " is missing '" + key + "'");
        }
        return value.toString().trim();
    }

    private static Set<DisasterType> hazards(Object value, String uri) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Manifest entry " + uri + " is missing 'hazards'");
        }
        Set<DisasterType> hazards = EnumSet.noneOf(DisasterType.class);
        for (Object item : list) {
            hazards.add(hazard(String.valueOf(item), uri));
        }
        return hazards;
    }

    private static DisasterType hazard(String name, String uri) {
        try {
            return DisasterType.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Manifest entry " + uri + " has unknown hazard '" + name + "'");
        }
    }

    // YAML reads an unquoted 2021-03-15 as a date object and a quoted one as text; accept both.
    private static LocalDate date(Object value, String uri) {
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof String string) {
            try {
                return LocalDate.parse(string.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Manifest entry " + uri + " has published_date '" + string + "', expected YYYY-MM-DD");
            }
        }
        throw new IllegalArgumentException("Manifest entry " + uri + " is missing 'published_date'");
    }
}
