package io.jenkins.plugins.bpe;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import io.jenkins.plugins.bpe.designer.BuildFormDesignerAction;
import io.jenkins.plugins.bpe.model.FormDesign;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Provides the "Enhanced Build-with-Parameters page" switch on the job configuration page.
 *
 * <p>The switch is the only thing this descriptor handles; the layout itself is stored by {@link FormDesignStore}
 * outside {@code config.xml} so that a Pipeline's {@code properties([...])} step cannot erase it. Because of that,
 * {@link DescriptorImpl#newInstance} writes the switch to the store and returns {@code null}: no job property is
 * ever added to the job.
 */
public class BuildParameterEnhancerJobProperty extends JobProperty<Job<?, ?>> {

    @Extension
    @Symbol("buildParameterEnhancer")
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return BuildParameterEnhancerConfiguration.isActive()
                    && ParameterizedJobMixIn.ParameterizedJob.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return Messages.BuildParameterEnhancerJobProperty_DisplayName();
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest2 req, JSONObject formData) {
            Job<?, ?> job = job(req);
            if (job != null && job.hasPermission(Item.CONFIGURE)) {
                boolean enabled = formData != null && formData.optBoolean("bpeEnabled", false);
                try {
                    FormDesign design = FormDesignStore.load(job);
                    if (design.isEnabled() != enabled) {
                        design.setEnabled(enabled);
                        FormDesignStore.save(job, design);
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not store the enhanced build form switch", e);
                }
            }
            return null; // the design is stored separately; nothing is added to config.xml
        }

        /** Current state of the switch, for config.jelly. */
        public boolean isEnabledForCurrentJob() {
            Job<?, ?> job = job(Stapler.getCurrentRequest2());
            return job != null && FormDesignStore.load(job).isEnabled();
        }

        public boolean isPublishedForCurrentJob() {
            Job<?, ?> job = job(Stapler.getCurrentRequest2());
            return job != null && FormDesignStore.load(job).getPublishedLayout() != null;
        }

        /** Link to the designer for the job being configured. */
        public String getDesignerUrlForCurrentJob() {
            Job<?, ?> job = job(Stapler.getCurrentRequest2());
            StaplerRequest2 req = Stapler.getCurrentRequest2();
            if (job == null || req == null) {
                return null;
            }
            return req.getContextPath() + "/" + job.getUrl() + BuildFormDesignerAction.URL_NAME + "/";
        }

        private static Job<?, ?> job(StaplerRequest2 req) {
            return req == null ? null : req.findAncestorObject(Job.class);
        }

        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());
    }
}
