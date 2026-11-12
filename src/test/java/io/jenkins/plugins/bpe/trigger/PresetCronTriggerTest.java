package io.jenkins.plugins.bpe.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.bpe.FormDesignStore;
import io.jenkins.plugins.bpe.TestJobs;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.ParameterPreset;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PresetCronTriggerTest {

    @Test
    void triggerFiresAtScheduledTimeWithPresetValues(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "preset-trigger");
        // Create a shared preset
        FormDesign design = FormDesignStore.load(job);
        Map<String, String> values = new HashMap<>();
        values.put("APP_BRANCH", "release");
        values.put("BUILD_CONFIG", "Debug");
        values.put("VERSION", "3.0.0");
        ParameterPreset preset = new ParameterPreset("test-preset", "Test Preset", "For testing", values);
        design.getGlobalPresets().add(preset);
        design.setEnabled(true);
        FormDesignStore.save(job, design);

        // Set up the trigger
        String spec = "* * * * * % Preset=test-preset";
        PresetCronTrigger trigger = new PresetCronTrigger(spec);
        trigger.start(job, true);

        // Fire at the current minute
        Calendar now = new GregorianCalendar();
        trigger.checkDue(now);
        r.waitUntilNoActivity();

        WorkflowRun run = job.getLastBuild();
        assertNotNull(run, "Build should have been triggered");
        r.assertBuildStatus(Result.SUCCESS, run);

        ParametersAction params = run.getAction(ParametersAction.class);
        assertEquals("release", params.getParameter("APP_BRANCH").getValue());
        assertEquals("Debug", params.getParameter("BUILD_CONFIG").getValue());
        assertEquals("3.0.0", params.getParameter("VERSION").getValue());

        // Check cause
        Cause cause = run.getCause(PresetScheduleCause.class);
        assertNotNull(cause, "Cause should be PresetScheduleCause");
        assertTrue(cause.getShortDescription().contains("test-preset"));
    }

    @Test
    void triggerWithOverridesReplacesPresetValues(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "override");
        FormDesign design = FormDesignStore.load(job);
        Map<String, String> values = new HashMap<>();
        values.put("APP_BRANCH", "main");
        values.put("VERSION", "1.0.0");
        ParameterPreset preset = new ParameterPreset("base", "Base", "", values);
        design.getGlobalPresets().add(preset);
        FormDesignStore.save(job, design);

        String spec = "* * * * * % Preset=base; VERSION=2.5.0";
        PresetCronTrigger trigger = new PresetCronTrigger(spec);
        trigger.start(job, true);
        trigger.checkDue(new GregorianCalendar());
        r.waitUntilNoActivity();

        WorkflowRun run = job.getLastBuild();
        assertNotNull(run);
        ParametersAction params = run.getAction(ParametersAction.class);
        assertEquals("main", params.getParameter("APP_BRANCH").getValue(), "Preset value");
        assertEquals("2.5.0", params.getParameter("VERSION").getValue(), "Override value");
    }

    @Test
    void presetNotFoundDoesNotBuildAndRecordsError(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "missing-preset");
        String spec = "* * * * * % Preset=DoesNotExist";
        PresetCronTrigger trigger = new PresetCronTrigger(spec);
        trigger.start(job, true);
        trigger.checkDue(new GregorianCalendar());
        r.waitUntilNoActivity();

        WorkflowRun run = job.getLastBuild();
        assertTrue(run == null || run.getNumber() == 0, "No build should have been triggered");
        assertFalse(trigger.getLastErrors().isEmpty(), "Error should be recorded");
        assertTrue(
                trigger.getLastErrors().get(1).contains("not found"),
                trigger.getLastErrors().get(1));
    }

    @Test
    void copiedJobHasScheduleCommentedOut(JenkinsRule r) throws Exception {
        WorkflowJob original = r.createProject(WorkflowJob.class, "original");
        original.setDefinition(new CpsFlowDefinition("echo 'ok'", true));
        original.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P1", "default", "")));
        FormDesign design = FormDesignStore.load(original);
        design.getGlobalPresets().add(new ParameterPreset("preset1", "Preset 1", "", Map.of("P1", "value1")));
        design.setEnabled(true);
        FormDesignStore.save(original, design);

        String spec = "H 2 * * * % Preset=preset1\nH 12 * * * % Preset=preset1";
        PresetCronTrigger trigger = new PresetCronTrigger(spec);
        trigger.start(original, true);
        original.addTrigger(trigger);
        original.save();

        WorkflowJob copy = (WorkflowJob) r.jenkins.copy(original, "copy");
        PresetCronTrigger copyTrigger = copy.getTriggers().values().stream()
                .filter(t -> t instanceof PresetCronTrigger)
                .map(t -> (PresetCronTrigger) t)
                .findFirst()
                .orElse(null);

        assertNotNull(copyTrigger, "Trigger should be copied");
        String copiedSpec = copyTrigger.getPresetSpec();
        assertTrue(copiedSpec.contains("# Copied from"), copiedSpec);
        assertTrue(copiedSpec.contains("# H 2 * * * % Preset=preset1"), "Lines should be commented: " + copiedSpec);
        assertTrue(copiedSpec.contains("# H 12 * * * % Preset=preset1"), copiedSpec);
    }

    @Test
    void pipelineRoundTripPreservesSpec(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-rt");
        String spec = "H 2 * * * % Preset=Nightly\nH 12 * * 1-5 % Preset=Daily";
        String pipeline = """
                properties([
                  parameters([string(name: 'P1', defaultValue: 'v1', description: '')]),
                  pipelineTriggers([presetCron('%s')])
                ])
                echo "ok"
                """.formatted(spec.replace("\n", "\\n"));
        job.setDefinition(new CpsFlowDefinition(pipeline, true));
        FormDesign design = FormDesignStore.load(job);
        design.getGlobalPresets().add(new ParameterPreset("Nightly", "Nightly", "", Map.of("P1", "nightly")));
        design.getGlobalPresets().add(new ParameterPreset("Daily", "Daily", "", Map.of("P1", "daily")));
        FormDesignStore.save(job, design);

        // Run once to apply the Jenkinsfile trigger
        r.buildAndAssertSuccess(job);

        PresetCronTrigger trigger = job.getTriggers().values().stream()
                .filter(t -> t instanceof PresetCronTrigger)
                .map(t -> (PresetCronTrigger) t)
                .findFirst()
                .orElse(null);

        assertNotNull(trigger, "Trigger should be set by Jenkinsfile");
        assertEquals(spec, trigger.getPresetSpec(), "Spec should round-trip");
        assertEquals(2, trigger.getEntries().size());
    }

    @Test
    void validationReportsUnknownPreset(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "validation");
        PresetCronTrigger.DescriptorImpl desc = r.jenkins.getDescriptorByType(PresetCronTrigger.DescriptorImpl.class);
        String result =
                desc.doCheckPresetSpec(job, "H 2 * * * % Preset=Unknown").toString();
        assertTrue(result.contains("not found"), result);
    }

    @Test
    void doesNotFireWhenMinuteDoesNotMatch(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "no-fire");
        FormDesign design = FormDesignStore.load(job);
        design.getGlobalPresets().add(new ParameterPreset("test", "Test", "", Map.of("VERSION", "1.0")));
        FormDesignStore.save(job, design);

        // Schedule for minute 59
        String spec = "59 * * * * % Preset=test";
        PresetCronTrigger trigger = new PresetCronTrigger(spec);
        trigger.start(job, true);

        // Check at minute 0 (should not match)
        Calendar now = new GregorianCalendar();
        now.set(Calendar.MINUTE, 0);
        trigger.checkDue(now);
        r.waitUntilNoActivity();

        WorkflowRun run = job.getLastBuild();
        assertTrue(run == null || run.getNumber() == 0, "No build should fire");
    }
}
