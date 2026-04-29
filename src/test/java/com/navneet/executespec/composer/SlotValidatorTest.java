package com.navneet.executespec.composer;

import com.navneet.executespec.composer.SlotValidator.SlotDef;
import com.navneet.executespec.composer.SlotValidator.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-339 — pins the cross-product slot validator behaviour.  Used
 * by both the migration apply path and the readymade instantiate
 * path (W5); breaking these cascades to both products.
 */
class SlotValidatorTest {

    @Test
    void requiredSlot_blank_violates() {
        SlotDef slot = new SlotDef("currency", "Currency", "TEXT", true, null);
        List<Violation> v = SlotValidator.validate(slot, "");
        assertEquals(1, v.size());
        assertEquals("REQUIRED", v.get(0).code());
    }

    @Test
    void optionalSlot_blank_isOk() {
        SlotDef slot = new SlotDef("note", "Note", "TEXT", false, null);
        assertTrue(SlotValidator.validate(slot, "").isEmpty());
        assertTrue(SlotValidator.validate(slot, null).isEmpty());
    }

    @Test
    void selectSlot_outOfOptions_violates() {
        SlotDef slot = new SlotDef("currency", "Currency", "SELECT", true,
            "[\"USD\",\"EUR\",\"INR\"]");
        List<Violation> v = SlotValidator.validate(slot, "JPY");
        assertEquals(1, v.size());
        assertEquals("NOT_IN_OPTIONS", v.get(0).code());
    }

    @Test
    void selectSlot_withinOptions_isOk() {
        SlotDef slot = new SlotDef("currency", "Currency", "SELECT", true,
            "[\"USD\",\"EUR\",\"INR\"]");
        assertTrue(SlotValidator.validate(slot, "USD").isEmpty());
    }

    @Test
    void selectSlot_emptyOptions_skipsCheck() {
        // No options array means we don't constrain — accept anything.
        SlotDef slot = new SlotDef("foo", "Foo", "SELECT", true, null);
        assertTrue(SlotValidator.validate(slot, "anything").isEmpty());
    }

    @Test
    void numberSlot_nonNumeric_violates() {
        SlotDef slot = new SlotDef("amount", "Amount", "NUMBER", true, null);
        List<Violation> v = SlotValidator.validate(slot, "not-a-number");
        assertEquals("NOT_A_NUMBER", v.get(0).code());
    }

    @Test
    void numberSlot_validNumber_isOk() {
        SlotDef slot = new SlotDef("amount", "Amount", "NUMBER", true, null);
        assertTrue(SlotValidator.validate(slot, "42").isEmpty());
        assertTrue(SlotValidator.validate(slot, "3.14").isEmpty());
    }

    @Test
    void booleanSlot_invalid_violates() {
        SlotDef slot = new SlotDef("flag", "Flag", "BOOLEAN", true, null);
        List<Violation> v = SlotValidator.validate(slot, "yes");
        assertEquals("NOT_A_BOOLEAN", v.get(0).code());
    }

    @Test
    void booleanSlot_validValues_areOk() {
        SlotDef slot = new SlotDef("flag", "Flag", "BOOLEAN", true, null);
        assertTrue(SlotValidator.validate(slot, "true").isEmpty());
        assertTrue(SlotValidator.validate(slot, "false").isEmpty());
        assertTrue(SlotValidator.validate(slot, "TRUE").isEmpty()); // case-insensitive
    }

    @Test
    void filePathSlot_absolutePath_violates() {
        SlotDef slot = new SlotDef("path", "Path", "FILE_PATH", true, null);
        List<Violation> v = SlotValidator.validate(slot, "/etc/passwd");
        assertEquals("INVALID_PATH", v.get(0).code());
    }

    @Test
    void filePathSlot_traversal_violates() {
        SlotDef slot = new SlotDef("path", "Path", "FILE_PATH", true, null);
        List<Violation> v = SlotValidator.validate(slot, "../escape");
        assertEquals("INVALID_PATH", v.get(0).code());
    }

    @Test
    void filePathSlot_relativePath_isOk() {
        SlotDef slot = new SlotDef("path", "Path", "FILE_PATH", true, null);
        assertTrue(SlotValidator.validate(slot, "src/auth/login.ts").isEmpty());
    }

    @Test
    void secretSlot_tooShort_violates() {
        SlotDef slot = new SlotDef("key", "API Key", "SECRET", true, null);
        List<Violation> v = SlotValidator.validate(slot, "abc");
        assertEquals("TOO_SHORT", v.get(0).code());
    }

    @Test
    void validateAll_unknownKey_violates() {
        List<SlotDef> slots = List.of(
            new SlotDef("a", "A", "TEXT", true, null)
        );
        List<Violation> v = SlotValidator.validateAll(slots,
            Map.of("a", "ok", "typo_key", "stray"));
        assertEquals(1, v.size());
        assertEquals("UNKNOWN_SLOT", v.get(0).code());
    }

    @Test
    void validateAll_collectsAllViolations() {
        // Two failing slots — both surface in one call.
        List<SlotDef> slots = List.of(
            new SlotDef("a", "A", "TEXT", true, null),
            new SlotDef("b", "B", "NUMBER", true, null)
        );
        List<Violation> v = SlotValidator.validateAll(slots, Map.of("b", "nope"));
        assertEquals(2, v.size());
    }
}
