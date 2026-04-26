package com.navneet.executespec.contracts.breaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JsonSchemaBreakingChangeClassifier} (E2.7) —
 * exercises every rule from plan §2.5 against minimal schema fixtures.
 */
class JsonSchemaBreakingChangeClassifierTest {

    private final JsonSchemaBreakingChangeClassifier classifier =
            new JsonSchemaBreakingChangeClassifier(new ObjectMapper());

    @Test
    void identicalSchemas_returnEmptyDiff() {
        String s = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";
        ContractDiff diff = classifier.classify(s, s);
        assertThat(diff.changes()).isEmpty();
        assertThat(diff.hasBreaking()).isFalse();
        assertThat(diff.confidence()).isEqualTo(ContractDiff.Confidence.DETERMINISTIC);
    }

    @Test
    void removedProperty_isBreaking() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}";
        String newS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.hasBreaking()).isTrue();
        assertThat(diff.changes()).anyMatch(c -> c.description().contains("'name'")
                && c.severity() == ContractDiff.Change.Severity.BREAKING);
    }

    @Test
    void typeChange_onExistingProperty_isBreaking() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"age\":{\"type\":\"integer\"}}}";
        String newS = "{\"type\":\"object\",\"properties\":{\"age\":{\"type\":\"string\"}}}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.hasBreaking()).isTrue();
        assertThat(diff.changes()).anyMatch(c -> c.description().contains("integer")
                && c.description().contains("string"));
    }

    @Test
    void addingPropertyToRequired_isBreaking() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[]}";
        String newS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.hasBreaking()).isTrue();
        assertThat(diff.changes()).anyMatch(c -> c.description().contains("added to required"));
    }

    @Test
    void removingFromRequired_isNonBreaking() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}";
        String newS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[]}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.hasBreaking()).isFalse();
        assertThat(diff.changes()).anyMatch(c -> c.description().contains("removed from required")
                && c.severity() == ContractDiff.Change.Severity.NON_BREAKING);
    }

    @Test
    void addingOptionalProperty_isNonBreaking() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";
        String newS = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"}}}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.hasBreaking()).isFalse();
        assertThat(diff.changes()).anyMatch(c -> c.description().contains("'email' added")
                && c.severity() == ContractDiff.Change.Severity.NON_BREAKING);
    }

    @Test
    void nestedPropertyChanges_walkRecursively() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"string\"}}}}}";
        String newS = "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"integer\"}}}}}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.hasBreaking()).isTrue();
        // Location should reflect nesting
        assertThat(diff.changes()).anyMatch(c ->
                c.location().contains("properties.user.properties.email"));
    }

    @Test
    void parseFailure_returnsUnclassified_doesNotThrow() {
        ContractDiff diff = classifier.classify("not-json", "also-not");
        assertThat(diff.confidence()).isEqualTo(ContractDiff.Confidence.UNCLASSIFIED);
        assertThat(diff.summary()).contains("parse failed");
    }

    @Test
    void summary_reflectsBreakingCount() {
        String oldS = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}}}";
        String newS = "{\"type\":\"object\",\"properties\":{}}";
        ContractDiff diff = classifier.classify(oldS, newS);
        assertThat(diff.summary()).contains("2 breaking change");
    }
}
