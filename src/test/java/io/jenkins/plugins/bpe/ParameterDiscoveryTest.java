package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.discovery.ParameterDiscovery;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Discovery reads the real ParameterDefinition objects and never exposes a sensitive default. */
@WithJenkins
class ParameterDiscoveryTest {

    private static Map<String, DiscoveredParameter> byName(List<DiscoveredParameter> l) {
        return l.stream().collect(Collectors.toMap(DiscoveredParameter::name, Function.identity()));
    }

    @Test
    void discoversEveryTypeWithItsRealNameAndType(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "sample");
        List<DiscoveredParameter> found = ParameterDiscovery.discover(job);
        assertEquals(
                job.getProperty(ParametersDefinitionProperty.class)
                        .getParameterDefinitions()
                        .size(),
                found.size(),
                "every definition is discovered");

        Map<String, DiscoveredParameter> m = byName(found);
        assertEquals("string", m.get("APP_BRANCH").kind());
        assertEquals("choice", m.get("BUILD_CONFIG").kind());
        assertEquals("boolean", m.get("BUILD_APP").kind());
        assertEquals("text", m.get("APP_FILES_FILTER").kind(), "text is not reported as string");
        assertEquals("password", m.get("DEPLOY_TOKEN").kind());
        assertEquals(List.of("Release", "Debug"), m.get("BUILD_CONFIG").choices());
        assertEquals("main", m.get("APP_BRANCH").defaultValue());
        assertEquals("true", m.get("BUILD_APP").defaultValue());
    }

    @Test
    void neverExposesAPasswordDefault(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "secrets");
        DiscoveredParameter token = byName(ParameterDiscovery.discover(job)).get("DEPLOY_TOKEN");
        assertTrue(token.sensitive());
        assertNull(token.defaultValue(), "the stored default must not leave the server");
    }

    @Test
    void rendersDescriptionsThroughTheMarkupFormatterAndStripsMarkupForSearch(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "markup");
        job.setDefinition(new CpsFlowDefinition("echo 'x'", true));
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("A", "", "<b>bold</b> & <script>alert(1)</script>")));
        DiscoveredParameter a = ParameterDiscovery.discover(job).get(0);
        assertNotNull(a.descriptionHtml());
        assertFalse(
                a.descriptionHtml().contains("<script>"),
                "the default formatter escapes markup: " + a.descriptionHtml());
        assertFalse(a.descriptionText().contains("<"), "search text carries no tags: " + a.descriptionText());
        assertTrue(a.descriptionText().contains("bold"));
    }

    @Test
    void aJobWithoutParametersYieldsNothing(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "plain");
        job.setDefinition(new CpsFlowDefinition("echo 'x'", true));
        assertTrue(ParameterDiscovery.discover(job).isEmpty());
    }
}
