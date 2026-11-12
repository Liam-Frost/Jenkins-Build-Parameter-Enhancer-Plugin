package io.jenkins.plugins.bpe.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.scheduler.Hash;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresetScheduleTest {

    @Test
    void emptySpecReturnsNoEntries() {
        assertEquals(0, PresetSchedule.parse(null, Hash.zero()).size());
        assertEquals(0, PresetSchedule.parse("", Hash.zero()).size());
        assertEquals(0, PresetSchedule.parse("   \n   ", Hash.zero()).size());
    }

    @Test
    void commentsAndBlankLinesAreSkipped() {
        String spec = "# This is a comment\n\n# Another comment\nH 2 * * * % Preset=Nightly";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(1, entries.size());
        assertEquals("Nightly", entries.get(0).presetName());
        assertEquals(4, entries.get(0).lineNumber());
    }

    @Test
    void simpleSpecIsParseCorrectly() {
        String spec = "H 2 * * * % Preset=Nightly";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(1, entries.size());
        PresetSchedule.Entry entry = entries.get(0);
        assertEquals("Nightly", entry.presetName());
        assertTrue(entry.overrides().isEmpty());
    }

    @Test
    void specWithOverridesIsParsedCorrectly() {
        String spec = "H 12 * * 1-5 % Preset=Daily smoke; BRANCH=develop; VERSION=2.0";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(1, entries.size());
        PresetSchedule.Entry entry = entries.get(0);
        assertEquals("Daily smoke", entry.presetName());
        assertEquals(2, entry.overrides().size());
        assertEquals("develop", entry.overrides().get("BRANCH"));
        assertEquals("2.0", entry.overrides().get("VERSION"));
    }

    @Test
    void multipleScheduleLinesAreParsed() {
        String spec = """
                H 2 * * * % Preset=Nightly
                H 12 * * 1-5 % Preset=Daily; BRANCH=develop
                """;
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(2, entries.size());
        assertEquals("Nightly", entries.get(0).presetName());
        assertEquals("Daily", entries.get(1).presetName());
    }

    @Test
    void missingPercentSeparatorThrows() {
        String spec = "H 2 * * * Preset=Nightly";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> PresetSchedule.parse(spec, Hash.zero()));
        assertTrue(ex.getMessage().contains("Line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("%"), ex.getMessage());
    }

    @Test
    void missingPresetNameThrows() {
        String spec = "H 2 * * * % BRANCH=develop";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> PresetSchedule.parse(spec, Hash.zero()));
        assertTrue(ex.getMessage().contains("Preset="), ex.getMessage());
    }

    @Test
    void emptyPresetNameThrows() {
        String spec = "H 2 * * * % Preset=";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> PresetSchedule.parse(spec, Hash.zero()));
        assertTrue(ex.getMessage().contains("Preset="), ex.getMessage());
    }

    @Test
    void invalidCronExpressionThrows() {
        String spec = "not-a-cron % Preset=Nightly";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> PresetSchedule.parse(spec, Hash.zero()));
        assertTrue(ex.getMessage().contains("Line 1"), ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("cron"), ex.getMessage());
    }

    @Test
    void invalidParamSyntaxThrows() {
        // KEY without = is not valid
        String spec = "H 2 * * * % Preset=Nightly; NOEQUALS";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> PresetSchedule.parse(spec, Hash.zero()));
        assertTrue(ex.getMessage().contains("KEY=value"), ex.getMessage());
    }

    @Test
    void tzLineSetsTimezoneForSubsequentEntries() {
        // Just check parsing doesn't throw and entries are created
        String spec = "TZ=UTC\nH 2 * * * % Preset=Nightly\nH 10 * * * % Preset=Morning";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(2, entries.size());
    }

    @Test
    void valueWithEqualsSignInValueIsSplitOnFirstEquals() {
        // KEY=val=ue should produce key=KEY, value=val=ue
        String spec = "H 2 * * * % Preset=Nightly; QUERY=foo=bar";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals("foo=bar", entries.get(0).overrides().get("QUERY"));
    }

    @Test
    void hTokenInCronIsAllowed() {
        // The H token makes the tab nondeterministic but parseable
        String spec = "H H(2-4) * * * % Preset=Nightly";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(1, entries.size());
    }

    @Test
    void lineNumberIsCorrectEvenWithComments() {
        String spec = "# comment\nH 2 * * * % Preset=A\n# another\nH 12 * * * % Preset=B";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals(2, entries.get(0).lineNumber()); // First entry is on line 2
        assertEquals(4, entries.get(1).lineNumber()); // Second is on line 4
    }

    @Test
    void presetNameIsTrimmed() {
        String spec = "H 2 * * * % Preset=  My Nightly  ";
        List<PresetSchedule.Entry> entries = PresetSchedule.parse(spec, Hash.zero());
        assertEquals("My Nightly", entries.get(0).presetName());
    }
}
