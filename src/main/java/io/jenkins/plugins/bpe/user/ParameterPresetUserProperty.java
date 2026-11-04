package io.jenkins.plugins.bpe.user;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import io.jenkins.plugins.bpe.model.ParameterPreset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * A user's personal parameter presets, per job full name. Only the owning user ever sees them.
 *
 * <p>Managed through {@code PresetApi} only; there is no section on the user's configure page, and
 * {@link #reconfigure} keeps the stored presets when that page is submitted.
 */
public class ParameterPresetUserProperty extends UserProperty {
    /** Job full name to the presets this user saved for that job. */
    private Map<String, List<ParameterPreset>> jobPresets = new HashMap<>();

    public ParameterPresetUserProperty() {}

    /** @return a live, mutable list; callers synchronize on this property while changing it. */
    public synchronized List<ParameterPreset> presetsFor(String jobFullName) {
        if (jobPresets == null) {
            jobPresets = new HashMap<>();
        }
        return jobPresets.computeIfAbsent(jobFullName, k -> new ArrayList<>());
    }

    /** Read-only view for listing; unlike {@link #presetsFor} it never adds an entry. */
    public synchronized List<ParameterPreset> snapshot(String jobFullName) {
        List<ParameterPreset> l = jobPresets == null ? null : jobPresets.get(jobFullName);
        return l == null ? List.of() : new ArrayList<>(l);
    }

    /** Drops the entry for a job that has no presets left, so the user's config.xml does not accumulate keys. */
    public synchronized void prune(String jobFullName) {
        if (jobPresets != null) {
            List<ParameterPreset> l = jobPresets.get(jobFullName);
            if (l != null && l.isEmpty()) {
                jobPresets.remove(jobFullName);
            }
        }
    }

    /** Moves presets along with a renamed or moved job. */
    public synchronized boolean rename(String oldFullName, String newFullName) {
        if (jobPresets == null || !jobPresets.containsKey(oldFullName)) {
            return false;
        }
        List<ParameterPreset> moved = jobPresets.remove(oldFullName);
        jobPresets.computeIfAbsent(newFullName, k -> new ArrayList<>()).addAll(moved);
        return true;
    }

    public synchronized boolean forget(String jobFullName) {
        return jobPresets != null && jobPresets.remove(jobFullName) != null;
    }

    @Override
    public UserProperty reconfigure(StaplerRequest2 req, JSONObject form) {
        return this;
    }

    /** @return the user's property, creating and attaching it if needed. */
    public static ParameterPresetUserProperty of(User user) throws java.io.IOException {
        ParameterPresetUserProperty p = user.getProperty(ParameterPresetUserProperty.class);
        if (p == null) {
            p = new ParameterPresetUserProperty();
            user.addProperty(p);
        }
        return p;
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {
        @Override
        public UserProperty newInstance(User user) {
            return null;
        }

        @Override
        public String getDisplayName() {
            return "Build parameter presets";
        }
    }
}
