package io.jenkins.plugins.bpe.trigger;

import hudson.model.Cause;
import io.jenkins.plugins.bpe.I18n;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records that a build was started by a preset cron schedule.
 */
public class PresetScheduleCause extends Cause {
    private final String presetName;
    private final String presetId;
    /** Stored as LinkedHashMap so XStream can serialize/deserialize safely. */
    private final Map<String, String> overrides;

    private final int lineNumber;

    public PresetScheduleCause(String presetName, String presetId, Map<String, String> overrides, int lineNumber) {
        this.presetName = presetName;
        this.presetId = presetId;
        this.overrides = new LinkedHashMap<>(overrides);
        this.lineNumber = lineNumber;
    }

    public String getPresetName() {
        return presetName;
    }

    public String getPresetId() {
        return presetId;
    }

    public Map<String, String> getOverrides() {
        return overrides;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public String getShortDescription() {
        if (overrides == null || overrides.isEmpty()) {
            return I18n.text("server.schedule.cause", presetName);
        }
        return I18n.text("server.schedule.causeWithOverrides", presetName, overrides.size());
    }
}
