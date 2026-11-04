package io.jenkins.plugins.bpe;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import io.jenkins.plugins.bpe.autolayout.AutoLayoutGenerator;
import io.jenkins.plugins.bpe.discovery.ChangeDetector;
import io.jenkins.plugins.bpe.discovery.ParameterDiscovery;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.FormLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;

/** Shared fixtures: a Pipeline job with the parameter set from the brief. */
final class TestJobs {

    static final String ECHO_SCRIPT = """
            def shown = ['APP_BRANCH', 'APP_COMMIT_HASH', 'BUILD_CONFIG', 'BUILD_ENV', 'VERSION',
                         'BUILD_APP', 'BUILD_ENGINE', 'CLEAN_WORKSPACE', 'PACKAGE_TYPE',
                         'ENGINE_BUILD_MODE', 'SIGN_BATCH_SIZE']
            for (name in shown) {
                echo "PARAM ${name}=${params[name]}"
            }
            echo 'PARAM DEPLOY_TOKEN=<hidden>'
            """;

    private TestJobs() {}

    static List<ParameterDefinition> sampleDefinitions() {
        List<ParameterDefinition> p = new ArrayList<>();
        for (String repo : new String[] {"APP", "SDK", "DOCS", "ENGINE"}) {
            p.add(new StringParameterDefinition(
                    repo + "_BRANCH", "main", "Branch of the " + repo + " repository to build from."));
            p.add(new StringParameterDefinition(
                    repo + "_COMMIT_HASH", "", "Optional commit hash. Overrides the branch when set."));
        }
        p.add(new ChoiceParameterDefinition("BUILD_CONFIG", new String[] {"Release", "Debug"}, "Build configuration."));
        p.add(new ChoiceParameterDefinition("BUILD_ENV", new String[] {"QA", "PROD"}, "Target environment."));
        p.add(new StringParameterDefinition("VERSION", "1.0.0", "Version number stamped into the artifacts."));
        for (String m : new String[] {"APP", "SDK", "DOCS", "ENGINE"}) {
            p.add(new BooleanParameterDefinition("BUILD_" + m, true, "Build the " + m + " module."));
        }
        p.add(new BooleanParameterDefinition("CLEAN_WORKSPACE", false, "Wipe the workspace before building."));
        p.add(new BooleanParameterDefinition("CLEAN_BUILD_CACHE", false, "Drop the compiler cache."));
        p.add(new StringParameterDefinition("CHANGELOG_SINCE", "", "Tag or commit to generate the changelog from."));
        p.add(new ChoiceParameterDefinition(
                "PACKAGE_TYPE", new String[] {"Personal", "Preload", "No"}, "Packaging strategy."));
        p.add(new TextParameterDefinition(
                "APP_FILES_FILTER",
                "",
                "One glob per line. Only matching files are packaged. Leave empty to package everything, which is"
                        + " what almost every build wants. This description is deliberately long so that the"
                        + " collapsed help behaviour can be checked."));
        p.add(new StringParameterDefinition("ENGINE_TARGET", "all", "Engine build target."));
        p.add(new ChoiceParameterDefinition("ENGINE_RUST_MODE", new String[] {"fast", "size"}, "Rust optimisation."));
        p.add(new ChoiceParameterDefinition(
                "ENGINE_BUILD_MODE", new String[] {"incr", "full"}, "incr: incremental build\nfull: full rebuild"));
        p.add(new BooleanParameterDefinition("ENABLE_SIGNING", true, "Sign the produced binaries."));
        p.add(new StringParameterDefinition("SIGN_BATCH_SIZE", "25", "Files signed per batch."));
        p.add(new StringParameterDefinition("TIMESTAMP_BATCH_SIZE", "50", "Files timestamped per batch."));
        p.add(new ChoiceParameterDefinition("LZMA_PROFILE", new String[] {"default", "max", "off"}, "Compression."));
        p.add(new PasswordParameterDefinition("DEPLOY_TOKEN", "s3cr3t-default", "Token used to upload artifacts."));
        return p;
    }

    static WorkflowJob sampleJob(JenkinsRule r, String name) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(ECHO_SCRIPT, true));
        job.addProperty(new ParametersDefinitionProperty(sampleDefinitions()));
        return job;
    }

    /** Generates and publishes a layout from the default rules, as an administrator would. */
    static FormLayout publishGeneratedLayout(Job<?, ?> job) throws IOException {
        var params = ParameterDiscovery.discover(job);
        FormLayout layout = AutoLayoutGenerator.generate(params, AutoLayoutGenerator.defaultRules());
        layout.setKnownParameters(ChangeDetector.snapshot(params));
        FormDesign design = FormDesignStore.load(job);
        design.publish(layout, "tester");
        design.setEnabled(true);
        FormDesignStore.save(job, design);
        return layout;
    }
}
