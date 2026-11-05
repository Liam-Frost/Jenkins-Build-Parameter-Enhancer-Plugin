package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bpe.autolayout.AutoLayoutGenerator;
import io.jenkins.plugins.bpe.autolayout.GroupingRule;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import org.junit.jupiter.api.Test;

/** Auto Generate Layout is deterministic and never loses a parameter. No Jenkins instance needed. */
class AutoLayoutGeneratorTest {

    private static DiscoveredParameter p(String name, String kind) {
        return new DiscoveredParameter(name, "C" + kind, kind, kind, null, null, null, null, null, false);
    }

    private static List<DiscoveredParameter> sampleParameters() {
        List<DiscoveredParameter> l = new ArrayList<>();
        for (String repo : new String[] {"APP", "SDK", "DOCS", "ENGINE"}) {
            l.add(p(repo + "_BRANCH", "string"));
            l.add(p(repo + "_COMMIT_HASH", "string"));
        }
        l.add(p("BUILD_CONFIG", "choice"));
        l.add(p("BUILD_ENV", "choice"));
        l.add(p("VERSION", "string"));
        for (String m : new String[] {"APP", "SDK", "DOCS", "ENGINE"}) {
            l.add(p("BUILD_" + m, "boolean"));
        }
        l.add(p("CLEAN_WORKSPACE", "boolean"));
        l.add(p("CLEAN_BUILD_CACHE", "boolean"));
        l.add(p("CHANGELOG_SINCE", "string"));
        l.add(p("PACKAGE_TYPE", "choice"));
        l.add(p("APP_FILES_FILTER", "text"));
        l.add(p("ENGINE_TARGET", "string"));
        l.add(p("ENGINE_RUST_MODE", "choice"));
        l.add(p("ENGINE_BUILD_MODE", "choice"));
        l.add(p("ENABLE_SIGNING", "boolean"));
        l.add(p("SIGN_BATCH_SIZE", "string"));
        l.add(p("TIMESTAMP_BATCH_SIZE", "string"));
        l.add(p("LZMA_PROFILE", "choice"));
        return l;
    }

    @Test
    void defaultRulesParse() {
        List<GroupingRule> rules = AutoLayoutGenerator.defaultRules();
        assertEquals(4, rules.size());
        assertEquals("source", rules.get(0).getGroupId());
        assertTrue(rules.get(3).isCollapsed(), "advanced options are collapsed by default");
    }

    @Test
    void groupsTheSampleParametersAsDocumented() {
        FormLayout l = AutoLayoutGenerator.generate(sampleParameters(), AutoLayoutGenerator.defaultRules());
        assertEquals(
                List.of("source", "config", "modules", "advanced"),
                l.getGroups().stream().map(GroupLayout::getId).toList());

        GroupLayout source = l.findGroup("source");
        assertEquals(8, source.getFields().size());
        assertEquals(
                List.of("APP_BRANCH", "APP_COMMIT_HASH"), source.getFields().subList(0, 2));

        assertEquals(
                List.of("BUILD_CONFIG", "BUILD_ENV", "VERSION"),
                l.findGroup("config").getFields());
        assertEquals(
                List.of("BUILD_APP", "BUILD_SDK", "BUILD_DOCS", "BUILD_ENGINE"),
                l.findGroup("modules").getFields());

        GroupLayout advanced = l.findGroup("advanced");
        assertTrue(advanced.isCollapsed());
        assertTrue(advanced.getFields()
                .containsAll(List.of(
                        "CLEAN_WORKSPACE",
                        "CHANGELOG_SINCE",
                        "PACKAGE_TYPE",
                        "ENGINE_BUILD_MODE",
                        "ENABLE_SIGNING",
                        "SIGN_BATCH_SIZE",
                        "LZMA_PROFILE")));
    }

    @Test
    void linksBranchWithItsCommitHash() {
        FormLayout l = AutoLayoutGenerator.generate(sampleParameters(), AutoLayoutGenerator.defaultRules());
        assertTrue(l.getField("APP_BRANCH").isLinkWithNext(), "branch shares a row with its commit hash");
        assertFalse(l.getField("APP_COMMIT_HASH").isLinkWithNext());
        assertEquals("full", l.getField("APP_FILES_FILTER").getWidth(), "text parameters take the full width");
    }

    @Test
    void pairsTheBranchFirstEvenWhenTheCommitHashesAreDeclaredFirst() {
        // A Jenkinsfile that lists every commit hash before every branch is as common as the other order.
        List<DiscoveredParameter> params = List.of(
                p("APP_COMMIT_HASH", "string"),
                p("ENGINE_COMMIT_HASH", "string"),
                p("APP_BRANCH", "git"),
                p("ENGINE_BRANCH", "git"));
        FormLayout l = AutoLayoutGenerator.generate(params, AutoLayoutGenerator.defaultRules());

        assertEquals(
                List.of("APP_BRANCH", "APP_COMMIT_HASH", "ENGINE_BRANCH", "ENGINE_COMMIT_HASH"),
                l.findGroup("source").getFields(),
                "each branch leads its own commit hash, at the position the pair's first half held");
        assertTrue(l.getField("APP_BRANCH").isLinkWithNext());
        assertTrue(l.getField("ENGINE_BRANCH").isLinkWithNext());
        assertFalse(l.getField("APP_COMMIT_HASH").isLinkWithNext(), "a commit hash never leads a pair");
    }

    @Test
    void everyParameterIsPlacedOnceOrLeftUnassigned() {
        List<DiscoveredParameter> params = new ArrayList<>(sampleParameters());
        params.add(p("SOMETHING_ODD", "string"));
        FormLayout l = AutoLayoutGenerator.generate(params, AutoLayoutGenerator.defaultRules());
        List<String> placed =
                l.getGroups().stream().flatMap(g -> g.getFields().stream()).toList();
        assertEquals(placed.size(), placed.stream().distinct().count(), "no parameter is placed twice");
        assertFalse(placed.contains("SOMETHING_ODD"), "unmatched parameters stay unassigned");
        assertTrue(l.getFields().containsKey("SOMETHING_ODD"), "but they do get field settings");
    }

    @Test
    void isDeterministic() {
        List<GroupingRule> rules = AutoLayoutGenerator.defaultRules();
        FormLayout a = AutoLayoutGenerator.generate(sampleParameters(), rules);
        FormLayout b = AutoLayoutGenerator.generate(sampleParameters(), rules);
        assertEquals(
                a.getGroups().stream().map(g -> g.getId() + g.getFields()).toList(),
                b.getGroups().stream().map(g -> g.getId() + g.getFields()).toList());
    }

    @Test
    void mergeKeepsExistingLabelsGroupsAndOrder() {
        List<GroupingRule> rules = AutoLayoutGenerator.defaultRules();
        FormLayout original = AutoLayoutGenerator.generate(sampleParameters(), rules);
        original.getField("BUILD_CONFIG").setLabel("构建配置");
        original.findGroup("config").setTitle("My config");
        original.setKnownParameters(io.jenkins.plugins.bpe.discovery.ChangeDetector.snapshot(sampleParameters()));

        List<DiscoveredParameter> next = new ArrayList<>(sampleParameters());
        next.add(p("NEW_BRANCH", "string"));
        FormLayout merged = AutoLayoutGenerator.merge(original, next, rules);

        assertEquals("构建配置", merged.getField("BUILD_CONFIG").getLabel(), "label override survives a sync");
        assertEquals("My config", merged.findGroup("config").getTitle(), "group title survives a sync");
        assertEquals("source", merged.findGroupOf("NEW_BRANCH").getId(), "the new parameter is placed by the rules");
        assertEquals(
                original.findGroup("modules").getFields(),
                merged.findGroup("modules").getFields(),
                "untouched groups keep their order");
    }

    @Test
    void mergeLeavesUnmatchedNewParametersUnassigned() {
        List<GroupingRule> rules = AutoLayoutGenerator.defaultRules();
        FormLayout original = AutoLayoutGenerator.generate(sampleParameters(), rules);
        List<DiscoveredParameter> next = new ArrayList<>(sampleParameters());
        next.add(p("ZZZ_WEIRD", "string"));
        FormLayout merged = AutoLayoutGenerator.merge(original, next, rules);
        assertNotNull(merged.getField("ZZZ_WEIRD"));
        assertEquals(null, merged.findGroupOf("ZZZ_WEIRD"));
    }

    @Test
    void globPatternsAreMatchedWholeAndCaseInsensitively() {
        GroupingRule r = GroupingRule.parseList(
                        JSONArray.fromObject("[{\"groupId\":\"g\",\"title\":\"g\",\"patterns\":[\"*_BRANCH\"]}]"))
                .get(0);
        assertTrue(r.matches(p("udcc_branch", "string")));
        assertFalse(r.matches(p("BRANCH_NAME", "string")));
    }

    @Test
    void onlyTypesRestrictsARule() {
        GroupingRule r = GroupingRule.parseList(JSONArray.fromObject(
                        "[{\"groupId\":\"g\",\"title\":\"g\",\"onlyTypes\":[\"boolean\"],\"patterns\":[\"BUILD_*\"]}]"))
                .get(0);
        assertTrue(r.matches(p("BUILD_X", "boolean")));
        assertFalse(r.matches(p("BUILD_X", "choice")));
    }
}
