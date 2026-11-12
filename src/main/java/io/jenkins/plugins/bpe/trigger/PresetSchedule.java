package io.jenkins.plugins.bpe.trigger;

import hudson.scheduler.CronTab;
import hudson.scheduler.Hash;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a multi-line preset schedule specification into individual cron entries with preset references and overrides.
 * <p>
 * Format: {@code <cron-expression> % Preset=<name>[; KEY=value; ...]}
 * <ul>
 *   <li>Lines starting with {@code #} are comments</li>
 *   <li>Lines starting with {@code TZ=} set the timezone for subsequent entries</li>
 *   <li>The {@code Preset=<name>} reference is required after the {@code %} separator</li>
 *   <li>Additional {@code KEY=value} overrides can follow, separated by {@code ;}</li>
 * </ul>
 */
public final class PresetSchedule {

    private PresetSchedule() {}

    /**
     * Parses a preset schedule specification.
     *
     * @param spec the multi-line schedule specification
     * @param hash used for evaluating {@code H} tokens in cron expressions
     * @return list of schedule entries
     * @throws IllegalArgumentException if the specification is invalid
     */
    public static List<Entry> parse(String spec, Hash hash) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        String[] lines = spec.split("\\r?\\n");
        String timezone = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNumber = i + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("TZ=")) {
                String tz = line.substring(3).trim();
                timezone = tz.isEmpty() ? null : tz;
                continue;
            }
            int sep = line.indexOf('%');
            if (sep == -1) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": missing '%' separator. Expected: <cron> % Preset=<name>");
            }
            String cronPart = line.substring(0, sep).trim();
            String paramPart = line.substring(sep + 1).trim();
            if (cronPart.isEmpty()) {
                throw new IllegalArgumentException("Line " + lineNumber + ": cron expression is empty");
            }
            if (paramPart.isEmpty()) {
                throw new IllegalArgumentException("Line " + lineNumber + ": preset reference is empty");
            }
            CronTab tab;
            try {
                tab = new CronTab(cronPart, lineNumber, hash, timezone);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": invalid cron expression: " + e.getMessage(), e);
            }
            Map<String, String> params = parseParams(paramPart, lineNumber);
            String presetName = params.get("Preset");
            if (presetName == null || presetName.isBlank()) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": Preset= is required after the % separator");
            }
            // Preset is not an override; remove it from the overrides map
            Map<String, String> overrides = new LinkedHashMap<>(params);
            overrides.remove("Preset");
            entries.add(new Entry(lineNumber, tab, presetName.trim(), overrides));
        }
        return entries;
    }

    private static Map<String, String> parseParams(String paramPart, int lineNumber) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : paramPart.split(";")) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq == -1) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": parameter must be in KEY=value format, found: " + pair);
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Line " + lineNumber + ": parameter key is empty in: " + pair);
            }
            result.put(key, value);
        }
        return result;
    }

    /**
     * A single schedule entry with its cron timing, preset reference, and parameter overrides.
     *
     * @param lineNumber the line number in the specification (1-based)
     * @param tab the parsed cron expression
     * @param presetName the name of the preset to use
     * @param overrides parameter overrides (does not include the Preset key)
     */
    public record Entry(int lineNumber, CronTab tab, String presetName, Map<String, String> overrides) {}
}
