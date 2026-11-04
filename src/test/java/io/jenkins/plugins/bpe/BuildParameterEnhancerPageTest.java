package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.bpe.model.FormDesign;
import java.util.List;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextInput;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The core guarantee: with the enhancement on, the page is regrouped and a build started from it still receives
 * exactly the right parameter values.
 *
 * <p>These tests run the real enhancer bundle in HtmlUnit, so a scripting regression fails the build.
 */
@WithJenkins
class BuildParameterEnhancerPageTest {

    private JenkinsRule.WebClient client(JenkinsRule r) {
        JenkinsRule.WebClient wc = r.createWebClient();
        wc.setJavaScriptEnabled(true);
        // Jenkins' own bundles log unrelated warnings in HtmlUnit; only our own failures matter here.
        wc.setCssErrorHandler(new org.htmlunit.SilentCssErrorHandler());
        wc.getOptions().setThrowExceptionOnScriptError(false);
        return wc;
    }

    private HtmlPage buildPage(JenkinsRule r, WorkflowJob job) throws Exception {
        JenkinsRule.WebClient wc = client(r);
        wc.setThrowExceptionOnFailingStatusCode(false); // core answers /build with 405 by design
        HtmlPage page = wc.getPage(job, "build?delay=0sec");
        wc.waitForBackgroundJavaScript(5000);
        return page;
    }

    @Test
    void theInterfaceFollowsTheBrowserLanguage(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "languages");
        TestJobs.publishGeneratedLayout(job);
        for (String[] c :
                new String[][] {{"en-US,en", "Expand all", "Other parameters"}, {"zh-CN,zh", "全部展开", "其他参数"}}) {
            JenkinsRule.WebClient wc = client(r);
            wc.setThrowExceptionOnFailingStatusCode(false);
            wc.addRequestHeader("Accept-Language", c[0]);
            HtmlPage page = wc.getPage(job, "build?delay=0sec");
            wc.waitForBackgroundJavaScript(5000);
            String toolbar = page.querySelector(".bpe-toolbar").getTextContent();
            assertTrue(toolbar.contains(c[1]), c[0] + " toolbar: " + toolbar);
            assertEquals(
                    c[2],
                    page.querySelector("[data-group-id=__other] .bpe-group__title")
                            .getTextContent());
        }
    }

    @Test
    void regroupsTheNativeControlsAndKeepsOneFieldPerParameter(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "grouped");
        TestJobs.publishGeneratedLayout(job);
        HtmlPage page = buildPage(r, job);

        assertFalse(page.querySelectorAll(".bpe-root").isEmpty(), "the enhanced container is present");
        assertEquals(
                4 + 1,
                page.querySelectorAll(".bpe-group").size(),
                "four generated groups plus the fallback group for DEPLOY_TOKEN");

        int definitions = job.getProperty(ParametersDefinitionProperty.class)
                .getParameterDefinitions()
                .size();
        assertEquals(definitions, page.querySelectorAll(".bpe-field").size(), "every parameter is rendered once");
        assertEquals(
                definitions,
                page.querySelectorAll("form[name=parameters] [name=parameter]").size(),
                "and still exactly one submit container each");

        HtmlElement advanced = page.querySelector("[data-group-id=advanced] .bpe-group__toggle");
        assertEquals("false", advanced.getAttribute("aria-expanded"), "advanced options start collapsed");
        HtmlElement source = page.querySelector("[data-group-id=source] .bpe-group__toggle");
        assertEquals("true", source.getAttribute("aria-expanded"), "common parameters start expanded");
    }

    @Test
    void submittingTheEnhancedFormPassesTheEditedValuesToThePipeline(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "submit");
        TestJobs.publishGeneratedLayout(job);
        HtmlPage page = buildPage(r, job);

        // Edit values inside an expanded group and inside a collapsed one.
        ((HtmlTextInput) page.querySelector("[data-param=APP_BRANCH] input[name=value]")).setValue("release/9.9");
        ((HtmlTextInput) page.querySelector("[data-param=APP_COMMIT_HASH] input[name=value]")).setValue("abc1234");
        ((HtmlSelect) page.querySelector("[data-param=BUILD_CONFIG] select[name=value]"))
                .setSelectedAttribute("Debug", true);
        ((HtmlSelect) page.querySelector("[data-param=BUILD_ENV] select[name=value]"))
                .setSelectedAttribute("PROD", true);
        ((HtmlCheckBoxInput) page.querySelector("[data-param=BUILD_ENGINE] input[type=checkbox]")).setChecked(false);
        // SIGN_BATCH_SIZE and ENGINE_BUILD_MODE live in the collapsed "advanced" group.
        ((HtmlTextInput) page.querySelector("[data-param=SIGN_BATCH_SIZE] input[name=value]")).setValue("40");
        ((HtmlSelect) page.querySelector("[data-param=ENGINE_BUILD_MODE] select[name=value]"))
                .setSelectedAttribute("full", true);

        HtmlForm form = page.getFormByName("parameters");
        r.submit(form);
        r.waitUntilNoActivity();

        WorkflowRun run = job.getLastBuild();
        assertNotNull(run, "a build was started");
        r.assertBuildStatus(Result.SUCCESS, run);

        ParametersAction action = run.getAction(ParametersAction.class);
        assertEquals("release/9.9", action.getParameter("APP_BRANCH").getValue());
        assertEquals("abc1234", action.getParameter("APP_COMMIT_HASH").getValue());
        assertEquals("Debug", action.getParameter("BUILD_CONFIG").getValue());
        assertEquals("PROD", action.getParameter("BUILD_ENV").getValue());
        assertEquals(Boolean.FALSE, action.getParameter("BUILD_ENGINE").getValue());
        assertEquals(Boolean.TRUE, action.getParameter("BUILD_APP").getValue(), "untouched default is kept");
        assertEquals("40", action.getParameter("SIGN_BATCH_SIZE").getValue(), "a value inside a collapsed group");
        assertEquals("full", action.getParameter("ENGINE_BUILD_MODE").getValue());
        assertEquals("1.0.0", action.getParameter("VERSION").getValue(), "defaults of untouched parameters");

        r.assertLogContains("PARAM APP_BRANCH=release/9.9", run);
        r.assertLogContains("PARAM BUILD_CONFIG=Debug", run);
        r.assertLogContains("PARAM BUILD_ENGINE=false", run);
        r.assertLogContains("PARAM SIGN_BATCH_SIZE=40", run);
    }

    @Test
    void collapsingAGroupDoesNotChangeWhatIsSubmitted(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "collapse");
        TestJobs.publishGeneratedLayout(job);
        HtmlPage page = buildPage(r, job);

        ((HtmlTextInput) page.querySelector("[data-param=VERSION] input[name=value]")).setValue("7.7.7");
        // Collapse every group, including the one holding VERSION.
        ((HtmlElement) page.querySelectorAll(".bpe-toolbar__actions button").get(1)).click();
        HtmlElement configToggle = page.querySelector("[data-group-id=config] .bpe-group__toggle");
        assertEquals("false", configToggle.getAttribute("aria-expanded"));
        assertTrue(
                ((HtmlElement) page.querySelector("[data-group-id=config] .bpe-group__body")).hasAttribute("hidden"),
                "the group body is marked hidden");

        r.submit(page.getFormByName("parameters"));
        r.waitUntilNoActivity();
        WorkflowRun run = job.getLastBuild();
        r.assertBuildStatus(Result.SUCCESS, run);
        assertEquals(
                "7.7.7",
                run.getAction(ParametersAction.class).getParameter("VERSION").getValue());
    }

    @Test
    void searchHidesOtherParametersWithoutDuplicatingOrClearingThem(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "search");
        TestJobs.publishGeneratedLayout(job);
        HtmlPage page = buildPage(r, job);

        ((HtmlTextInput) page.querySelector("[data-param=VERSION] input[name=value]")).setValue("3.2.1");
        org.htmlunit.html.HtmlInput search = page.querySelector(".bpe-toolbar__input");
        search.setValue("commit");
        search.fireEvent("input");
        page.getWebClient().waitForBackgroundJavaScript(1000);

        long visible = page.querySelectorAll(".bpe-field").stream()
                .map(e -> (HtmlElement) e)
                .filter(e -> !e.getAttribute("class").contains("bpe-hidden"))
                .count();
        assertEquals(
                5,
                visible,
                "only the five commit-mentioning parameters match (four commit hash fields + CHANGELOG_SINCE)");
        assertEquals(
                job.getProperty(ParametersDefinitionProperty.class)
                        .getParameterDefinitions()
                        .size(),
                page.querySelectorAll("form[name=parameters] [name=parameter]").size(),
                "search creates no extra input");
        HtmlElement sourceToggle = page.querySelector("[data-group-id=source] .bpe-group__toggle");
        assertEquals("true", sourceToggle.getAttribute("aria-expanded"), "the matching group is expanded");

        r.submit(page.getFormByName("parameters"));
        r.waitUntilNoActivity();
        assertEquals(
                "3.2.1",
                job.getLastBuild()
                        .getAction(ParametersAction.class)
                        .getParameter("VERSION")
                        .getValue(),
                "a value typed before searching survives");
    }

    @Test
    void appliesLabelsSummariesAndHelpText(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "labels");
        var layout = TestJobs.publishGeneratedLayout(job);
        var field = layout.getFields().get("ENGINE_BUILD_MODE");
        field.setLabel("Engine 构建模式");
        field.setShortDescription("选择增量构建或完整构建");
        field.setHelpText("incr: incremental build. full: full rebuild.");
        FormDesign design = FormDesignStore.load(job);
        design.publish(layout, "tester");
        FormDesignStore.save(job, design);

        HtmlPage page = buildPage(r, job);
        // ENGINE_BUILD_MODE is in the "advanced" group which starts collapsed; expand it first.
        HtmlElement advancedToggle = page.querySelector("[data-group-id=advanced] .bpe-group__toggle");
        advancedToggle.click();
        page.getWebClient().waitForBackgroundJavaScript(500);

        HtmlElement wrapper = page.querySelector("[data-param=ENGINE_BUILD_MODE]");
        assertEquals("Engine 构建模式", wrapper.querySelector(".bpe-field__label").getTextContent());
        assertEquals(
                "ENGINE_BUILD_MODE",
                wrapper.querySelector(".bpe-chip").getTextContent(),
                "the real parameter name stays visible");
        assertEquals("选择增量构建或完整构建", wrapper.querySelector(".bpe-field__summary").getTextContent());

        HtmlElement help = wrapper.querySelector(".bpe-help");
        assertTrue(help.hasAttribute("hidden"), "help starts closed");
        ((HtmlElement) wrapper.querySelector(".bpe-help-button")).click();
        assertFalse(help.hasAttribute("hidden"), "clicking the icon opens it");
        assertTrue(help.getTextContent().contains("incremental build"));
        assertTrue(help.getTextContent().contains("full rebuild"));

        // A parameter without custom help falls back to the Jenkins description.
        HtmlElement filter = page.querySelector("[data-param=APP_FILES_FILTER]");
        assertTrue(filter.querySelector(".bpe-field__summary").getTextContent().contains("One glob per line"));
    }

    @Test
    void doesNotTouchThePageWhenTheJobIsNotEnabled(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "disabled");
        TestJobs.publishGeneratedLayout(job);
        FormDesign design = FormDesignStore.load(job);
        design.setEnabled(false);
        FormDesignStore.save(job, design);

        HtmlPage page = buildPage(r, job);
        assertTrue(page.querySelectorAll(".bpe-root").isEmpty(), "the native page is served unchanged");
        assertFalse(
                page.querySelectorAll("form[name=parameters] [name=parameter]").isEmpty());
    }

    @Test
    void doesNotTouchThePageWithoutAPublishedLayout(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "nolayout");
        assertTrue(buildPage(r, job).querySelectorAll(".bpe-root").isEmpty());
    }

    @Test
    void theClassicQueryParameterRestoresTheNativePage(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "optout");
        TestJobs.publishGeneratedLayout(job);
        JenkinsRule.WebClient wc = client(r);
        wc.setThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = wc.getPage(job, "build?bpe=classic");
        wc.waitForBackgroundJavaScript(2000);
        assertTrue(page.querySelectorAll(".bpe-root").isEmpty());
    }

    @Test
    void theGlobalKillSwitchRestoresTheNativePage(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "killswitch");
        TestJobs.publishGeneratedLayout(job);
        BuildParameterEnhancerConfiguration.get().setEnabled(false);
        try {
            assertTrue(buildPage(r, job).querySelectorAll(".bpe-root").isEmpty());
        } finally {
            BuildParameterEnhancerConfiguration.get().setEnabled(true);
        }
    }

    @Test
    void aSubGroupIsShownAtItsPositionBetweenTheParentsParameters(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "nested-position");
        job.setDefinition(new CpsFlowDefinition("echo 'ok'", true));
        job.addProperty(new ParametersDefinitionProperty(List.of(
                new StringParameterDefinition("A1", "", ""),
                new StringParameterDefinition("A2", "", ""),
                new StringParameterDefinition("S1", "", ""))));
        io.jenkins.plugins.bpe.model.FormLayout layout = new io.jenkins.plugins.bpe.model.FormLayout();
        io.jenkins.plugins.bpe.model.GroupLayout parent =
                new io.jenkins.plugins.bpe.model.GroupLayout("parent", "Parent");
        parent.setFields(List.of("A1", "A2"));
        io.jenkins.plugins.bpe.model.GroupLayout sub = new io.jenkins.plugins.bpe.model.GroupLayout("sub", "Sub");
        sub.setFields(List.of("S1"));
        sub.setParentId("parent");
        sub.setPosition(1);
        layout.getGroups().add(parent);
        layout.getGroups().add(sub);
        FormDesign design = FormDesignStore.load(job);
        design.publish(layout, "tester");
        design.setEnabled(true);
        FormDesignStore.save(job, design);

        HtmlPage page = buildPage(r, job);
        HtmlElement body = page.querySelector(".bpe-group[data-group-id=parent] > .bpe-group__body");
        assertNotNull(body, "the parent group is rendered");
        List<String> order = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomElement child : body.getChildElements()) {
            String param = child.getAttribute("data-param");
            String group = child.getAttribute("data-group-id");
            order.add(!param.isEmpty() ? param : "[" + group + "]");
        }
        assertEquals(List.of("A1", "[sub]", "A2"), order);
    }

    @Test
    void parametersAddedAfterPublishingStayUsable(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "added");
        job.setDefinition(new CpsFlowDefinition("echo \"PARAM A=${params.A}\"; echo \"PARAM B=${params.B}\"", true));
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("A", "a", "First")));
        TestJobs.publishGeneratedLayout(job);

        // Simulate the Jenkinsfile gaining a parameter.
        job.removeProperty(ParametersDefinitionProperty.class);
        job.addProperty(new ParametersDefinitionProperty(List.of(
                new StringParameterDefinition("A", "a", "First"), new StringParameterDefinition("B", "b", "Second"))));

        HtmlPage page = buildPage(r, job);
        HtmlElement added = page.querySelector("[data-param=B]");
        assertNotNull(added, "a parameter the layout does not mention is still rendered");
        ((HtmlTextInput) added.querySelector("input[name=value]")).setValue("typed");

        r.submit(page.getFormByName("parameters"));
        r.waitUntilNoActivity();
        WorkflowRun run = job.getLastBuild();
        r.assertBuildStatus(Result.SUCCESS, run);
        assertEquals(
                "typed", run.getAction(ParametersAction.class).getParameter("B").getValue());
        r.assertLogContains("PARAM B=typed", run);
    }

    @Test
    void aRemovedParameterDoesNotBreakThePage(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "removed");
        TestJobs.publishGeneratedLayout(job);
        var kept = TestJobs.sampleDefinitions().stream()
                .filter(d -> !d.getName().equals("VERSION"))
                .toList();
        job.removeProperty(ParametersDefinitionProperty.class);
        job.addProperty(new ParametersDefinitionProperty(kept));

        HtmlPage page = buildPage(r, job);
        assertFalse(page.querySelectorAll(".bpe-root").isEmpty(), "the page is still enhanced");
        assertEquals(kept.size(), page.querySelectorAll(".bpe-field").size());
        r.submit(page.getFormByName("parameters"));
        r.waitUntilNoActivity();
        assertEquals(Result.SUCCESS, job.getLastBuild().getResult());
    }

    @Test
    void aPasswordParameterKeepsItsNativeControlAndSubmitsCorrectly(JenkinsRule r) throws Exception {
        WorkflowJob job = TestJobs.sampleJob(r, "password");
        TestJobs.publishGeneratedLayout(job);
        HtmlPage page = buildPage(r, job);

        HtmlElement wrapper = page.querySelector("[data-param=DEPLOY_TOKEN]");
        assertNotNull(wrapper, "the password parameter is rendered");
        assertFalse(
                page.asXml().contains("s3cr3t-default"),
                "the configured default is not written into the page by the plugin");
        // Core renders a password parameter as its concealed widget: a hidden value input carrying the
        // "<DEFAULT>" sentinel, plus a "Change Password" button that swaps in a real password field.
        // The plugin moves that whole widget untouched, so the sentinel is what submits unless the user changes it.
        HtmlElement hidden = wrapper.querySelector("input.complex-password-field[name=value]");
        assertNotNull(hidden, "its native concealed control is used, not a re-created input");
        assertNotNull(wrapper.querySelector(".hidden-password-update-btn"), "the Change Password button moved too");

        r.submit(page.getFormByName("parameters"));
        r.waitUntilNoActivity();
        WorkflowRun run = job.getLastBuild();
        r.assertBuildStatus(Result.SUCCESS, run);
        assertNotNull(run.getAction(ParametersAction.class).getParameter("DEPLOY_TOKEN"));
        r.assertLogNotContains("s3cr3t-default", run);
    }
}
