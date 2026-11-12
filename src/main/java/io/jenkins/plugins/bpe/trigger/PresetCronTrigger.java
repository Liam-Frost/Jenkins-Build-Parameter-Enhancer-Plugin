package io.jenkins.plugins.bpe.trigger;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.SimpleParameterDefinition;
import hudson.scheduler.Hash;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.bpe.BuildParameterEnhancerConfiguration;
import io.jenkins.plugins.bpe.FormDesignStore;
import io.jenkins.plugins.bpe.I18n;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.ParameterPreset;
import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * A trigger that schedules builds using shared presets. Each schedule line references a preset by name and can
 * include additional parameter overrides.
 * <p>
 * Format: {@code H 2 * * * % Preset=Nightly}<br>
 * {@code H 12 * * 1-5 % Preset=Daily smoke; BRANCH=develop}
 */
public class PresetCronTrigger extends Trigger<Job<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(PresetCronTrigger.class.getName());

    private String presetSpec;
    private transient List<PresetSchedule.Entry> entries;
    private transient Map<Integer, String> lastErrors;

    @DataBoundConstructor
    public PresetCronTrigger(String presetSpec) {
        // No-arg super: core would parse the spec as a plain cron list and reject the "%" part.
        super();
        this.presetSpec = presetSpec == null ? "" : presetSpec;
        this.lastErrors = new HashMap<>();
    }

    public String getPresetSpec() {
        return presetSpec;
    }

    @Override
    public void start(Job<?, ?> project, boolean newInstance) {
        super.start(project, newInstance);
        parseEntries();
    }

    /** Replaces the schedule, used to comment out the lines of a copied job. */
    @Restricted(NoExternalUse.class)
    public void replacePresetSpec(String spec) {
        this.presetSpec = spec == null ? "" : spec;
        parseEntries();
    }

    @Override
    protected Object readResolve() throws ObjectStreamException {
        super.readResolve();
        if (lastErrors == null) {
            lastErrors = new HashMap<>();
        }
        parseEntries();
        return this;
    }

    private void parseEntries() {
        try {
            entries = PresetSchedule.parse(presetSpec, job == null ? Hash.zero() : Hash.from(job.getFullName()));
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Invalid preset schedule spec in " + (job == null ? "?" : job.getFullName()), e);
            entries = List.of();
        }
    }

    /**
     * Checks if any schedule entry is due at the given time and fires it. Package-private for testing.
     */
    void checkDue(Calendar cal) {
        if (!BuildParameterEnhancerConfiguration.isActive()) {
            LOGGER.fine("Build Parameter Enhancer is disabled; skipping preset schedules");
            return;
        }
        if (entries == null || entries.isEmpty()) {
            return;
        }
        // Truncate to the minute boundary so CronTab.ceil gives consistent results
        // regardless of the second/millisecond the caller passes in.
        Calendar minute = new GregorianCalendar(cal.getTimeZone());
        minute.setTimeInMillis(cal.getTimeInMillis());
        minute.set(Calendar.SECOND, 0);
        minute.set(Calendar.MILLISECOND, 0);

        for (PresetSchedule.Entry entry : entries) {
            // ceil(minute) returns the same minute when the cron expression matches it;
            // it returns a later minute otherwise.
            // ceil modifies and returns its argument, so give it a copy.
            Calendar next = entry.tab().ceil((Calendar) minute.clone());
            boolean isDue = next.get(Calendar.YEAR) == minute.get(Calendar.YEAR)
                    && next.get(Calendar.DAY_OF_YEAR) == minute.get(Calendar.DAY_OF_YEAR)
                    && next.get(Calendar.HOUR_OF_DAY) == minute.get(Calendar.HOUR_OF_DAY)
                    && next.get(Calendar.MINUTE) == minute.get(Calendar.MINUTE);
            if (isDue) {
                fire(entry, cal);
            }
        }
    }

    private void fire(PresetSchedule.Entry entry, Calendar cal) {
        Job<?, ?> owner = job;
        if (owner == null) {
            LOGGER.warning("Preset schedule fired before the trigger was started; skipping");
            return;
        }
        try {
            ParametersAction params = buildParametersAction(owner, entry);
            Cause cause = new PresetScheduleCause(entry.presetName(), "", entry.overrides(), entry.lineNumber());
            ParameterizedJobMixIn.scheduleBuild2(owner, 0, new CauseAction(cause), params);
            lastErrors.remove(entry.lineNumber());
            LOGGER.fine(() -> "Scheduled build for " + owner.getFullName() + " using preset " + entry.presetName());
        } catch (Exception e) {
            String msg = "Failed to schedule build for " + owner.getFullName() + " (line " + entry.lineNumber()
                    + ", preset " + entry.presetName() + "): " + e.getMessage();
            LOGGER.log(Level.WARNING, msg, e);
            lastErrors.put(entry.lineNumber(), e.getMessage());
        }
    }

    private static ParametersAction buildParametersAction(Job<?, ?> job, PresetSchedule.Entry entry) {
        FormDesign design = FormDesignStore.load(job);
        ParameterPreset preset = design.getGlobalPresets().stream()
                .filter(p -> entry.presetName().equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Preset not found: " + entry.presetName()));

        ParametersDefinitionProperty prop = job.getProperty(ParametersDefinitionProperty.class);
        if (prop == null) {
            throw new IllegalStateException("Job has no parameters");
        }

        List<ParameterValue> values = new ArrayList<>();
        for (ParameterDefinition def : prop.getParameterDefinitions()) {
            String name = def.getName();
            // Priority: overrides > preset > default
            String valueStr =
                    entry.overrides().getOrDefault(name, preset.getValues().get(name));
            if (valueStr == null) {
                // Use default
                values.add(def.getDefaultParameterValue());
                continue;
            }
            // Reject password overrides (would store plaintext in config.xml)
            if (def instanceof PasswordParameterDefinition && entry.overrides().containsKey(name)) {
                throw new IllegalArgumentException("Password parameter cannot be overridden in schedule: " + name);
            }
            try {
                ParameterValue val;
                if (def instanceof SimpleParameterDefinition) {
                    val = ((SimpleParameterDefinition) def).createValue(valueStr);
                } else {
                    // Fallback for non-simple parameters (e.g., GitParameterDefinition)
                    val = def.createValue(null, valueStr);
                }
                if (val == null) {
                    throw new IllegalArgumentException("Invalid value for parameter " + name + ": " + valueStr);
                }
                values.add(val);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for parameter " + name + ": " + valueStr, e);
            }
        }
        // Check for unknown overrides
        for (String key : entry.overrides().keySet()) {
            if (prop.getParameterDefinition(key) == null) {
                throw new IllegalArgumentException("Unknown parameter in override: " + key);
            }
        }
        return new ParametersAction(values);
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        return List.of(new PresetScheduleAction(this));
    }

    public List<PresetSchedule.Entry> getEntries() {
        return entries == null ? List.of() : entries;
    }

    public Map<Integer, String> getLastErrors() {
        return lastErrors == null ? Map.of() : Map.copyOf(lastErrors);
    }

    @Extension
    @Symbol("presetCron")
    public static class DescriptorImpl extends TriggerDescriptor {
        @Override
        public boolean isApplicable(Item item) {
            return item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return I18n.text("server.presetCron.displayName");
        }

        @POST
        public FormValidation doCheckPresetSpec(@AncestorInPath Item item, @QueryParameter String value) {
            if (item == null) {
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                List<PresetSchedule.Entry> entries = PresetSchedule.parse(value, Hash.from(item.getFullName()));
                if (entries.isEmpty()) {
                    return FormValidation.warning(I18n.text("server.presetCron.validation.noEntries"));
                }
                // Validate presets and parameters
                Job<?, ?> job = (Job<?, ?>) item;
                FormDesign design = FormDesignStore.load(job);
                ParametersDefinitionProperty prop = job.getProperty(ParametersDefinitionProperty.class);
                List<String> warnings = new ArrayList<>();
                for (PresetSchedule.Entry entry : entries) {
                    // Check preset exists
                    boolean found = design.getGlobalPresets().stream()
                            .anyMatch(p -> entry.presetName().equals(p.getName()));
                    if (!found) {
                        warnings.add("Line " + entry.lineNumber() + ": preset not found: " + entry.presetName());
                        continue;
                    }
                    // Check for password overrides
                    if (prop != null) {
                        for (String key : entry.overrides().keySet()) {
                            ParameterDefinition def = prop.getParameterDefinition(key);
                            if (def == null) {
                                warnings.add("Line " + entry.lineNumber() + ": unknown parameter: " + key);
                            } else if (def instanceof PasswordParameterDefinition) {
                                warnings.add("Line " + entry.lineNumber()
                                        + ": password parameter cannot be overridden: " + key);
                            }
                        }
                    }
                }
                if (!warnings.isEmpty()) {
                    return FormValidation.warning(String.join("<br>", warnings));
                }
                // Show next run times
                Calendar now = new GregorianCalendar();
                List<String> nextRuns = new ArrayList<>();
                for (PresetSchedule.Entry entry : entries) {
                    // ceil modifies its argument; give it a copy.
                    Calendar next = entry.tab().ceil((Calendar) now.clone());
                    nextRuns.add("Line " + entry.lineNumber() + ": " + entry.presetName() + " → "
                            + String.format("%tF %<tT", next));
                }
                return FormValidation.okWithMarkup(entries.size() + " schedule(s)<br>" + String.join("<br>", nextRuns));
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
