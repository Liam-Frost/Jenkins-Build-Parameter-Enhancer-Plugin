package io.jenkins.plugins.bpe.trigger;

import hudson.model.Action;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

/**
 * Job action that displays the preset schedules and their status on the job's main page.
 */
public class PresetScheduleAction implements Action {
    private final PresetCronTrigger trigger;

    public PresetScheduleAction(PresetCronTrigger trigger) {
        this.trigger = trigger;
    }

    @Override
    public String getIconFileName() {
        return null; // Not shown in left sidebar
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }

    /** For Jelly view. Returns schedule info: preset name, next run time, last error. */
    public List<ScheduleInfo> getSchedules() {
        Calendar now = new GregorianCalendar();
        return trigger.getEntries().stream()
                .map(entry -> {
                    // ceil modifies its argument; give it a copy.
                    Calendar next = entry.tab().ceil((Calendar) now.clone());
                    String error = trigger.getLastErrors().get(entry.lineNumber());
                    return new ScheduleInfo(entry.lineNumber(), entry.presetName(), entry.overrides(), next, error);
                })
                .toList();
    }

    /** Schedule info bean — uses conventional getters for Jelly/JEXL compatibility. */
    public static final class ScheduleInfo {
        private final int lineNumber;
        private final String presetName;
        private final Map<String, String> overrides;
        private final Calendar nextRun;
        private final String error;

        public ScheduleInfo(
                int lineNumber, String presetName, Map<String, String> overrides, Calendar nextRun, String error) {
            this.lineNumber = lineNumber;
            this.presetName = presetName;
            this.overrides = Map.copyOf(overrides);
            this.nextRun = nextRun;
            this.error = error;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getPresetName() {
            return presetName;
        }

        public Map<String, String> getOverrides() {
            return overrides;
        }

        public Calendar getNextRun() {
            return nextRun;
        }

        public String getError() {
            return error;
        }

        public String getNextRunFormatted() {
            if (nextRun == null) return "—";
            return String.format("%tF %<tT", nextRun);
        }

        public String getOverridesSummary() {
            if (overrides.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            overrides.forEach((k, v) -> {
                if (sb.length() > 0) sb.append(", ");
                sb.append(k).append("=").append(v);
            });
            return sb.toString();
        }
    }
}
