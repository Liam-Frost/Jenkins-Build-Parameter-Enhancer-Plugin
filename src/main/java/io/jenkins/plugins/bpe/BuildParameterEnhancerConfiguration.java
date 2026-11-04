package io.jenkins.plugins.bpe;

import hudson.Extension;
import hudson.util.FormValidation;
import io.jenkins.plugins.bpe.autolayout.AutoLayoutGenerator;
import io.jenkins.plugins.bpe.autolayout.GroupingRule;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Global settings: a kill switch for all jobs and the default Auto Generate Layout rules. */
@Extension
@Symbol("buildParameterEnhancer")
public class BuildParameterEnhancerConfiguration extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(BuildParameterEnhancerConfiguration.class.getName());

    /** System property that disables the enhanced page everywhere without touching configuration. */
    public static final String KILL_SWITCH = BuildParameterEnhancerConfiguration.class.getName() + ".disabled";

    private boolean enabled = true;
    private String rulesJson;

    public BuildParameterEnhancerConfiguration() {
        load();
    }

    public static BuildParameterEnhancerConfiguration get() {
        return GlobalConfiguration.all().get(BuildParameterEnhancerConfiguration.class);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    /** @return {@code true} unless disabled globally or by the {@link #KILL_SWITCH} system property. */
    public static boolean isActive() {
        if (Boolean.getBoolean(KILL_SWITCH)) {
            return false;
        }
        BuildParameterEnhancerConfiguration c = get();
        return c == null || c.isEnabled();
    }

    public String getRulesJson() {
        return rulesJson == null || rulesJson.isBlank() ? AutoLayoutGenerator.defaultRulesJson() : rulesJson;
    }

    @DataBoundSetter
    public void setRulesJson(String rulesJson) {
        String v = rulesJson == null ? null : rulesJson.strip();
        if (v != null
                && (v.isEmpty()
                        || v.equals(AutoLayoutGenerator.defaultRulesJson().strip()))) {
            v = null;
        }
        if (v != null) {
            GroupingRule.parseList(JSONArray.fromObject(v)); // throws on invalid input
        }
        this.rulesJson = v;
        save();
    }

    /** The configured rules, falling back to the defaults if the stored JSON became invalid. */
    public List<GroupingRule> getRules() {
        if (rulesJson != null) {
            try {
                return GroupingRule.parseList(JSONArray.fromObject(rulesJson));
            } catch (JSONException | IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Invalid grouping rules in global configuration, using defaults", e);
            }
        }
        return AutoLayoutGenerator.defaultRules();
    }

    @POST
    public FormValidation doCheckRulesJson(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            List<GroupingRule> rules = GroupingRule.parseList(JSONArray.fromObject(value));
            return FormValidation.ok(rules.size() + " rule(s)");
        } catch (JSONException | IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }
}
