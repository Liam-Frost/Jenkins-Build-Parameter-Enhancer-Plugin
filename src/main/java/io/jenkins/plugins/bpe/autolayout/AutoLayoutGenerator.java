package io.jenkins.plugins.bpe.autolayout;

import io.jenkins.plugins.bpe.I18n;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.model.FieldLayout;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.json.JSONArray;

/**
 * Builds a suggested layout from {@link GroupingRule}s. Deterministic: the same parameters and rules always give
 * the same layout. Parameters that match no rule stay unassigned (the designer shows them under "Unassigned
 * Parameters" and the build page renders them in the fallback group), so nothing is ever dropped.
 */
public final class AutoLayoutGenerator {
    private static final String[] BRANCH_SUFFIXES = {"_BRANCH"};
    private static final String[] COMMIT_SUFFIXES = {
        "_COMMIT_HASH", "_COMMIT", "_COMMIT_ID", "_HASH", "_SHA", "_REVISION"
    };

    private AutoLayoutGenerator() {}

    public static List<GroupingRule> defaultRules() {
        try (InputStream in = AutoLayoutGenerator.class.getResourceAsStream("default-rules.json")) {
            if (in == null) {
                throw new IllegalStateException("default-rules.json missing");
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return GroupingRule.parseList(JSONArray.fromObject(json));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String defaultRulesJson() {
        JSONArray arr = new JSONArray();
        for (GroupingRule r : defaultRules()) {
            arr.add(r.toJson());
        }
        return arr.toString(2);
    }

    /** Generates a fresh layout, ignoring any existing one. */
    public static FormLayout generate(List<DiscoveredParameter> params, List<GroupingRule> rules) {
        FormLayout layout = new FormLayout();
        Map<String, GroupLayout> byId = new LinkedHashMap<>();
        for (DiscoveredParameter p : params) {
            GroupingRule rule = firstMatch(p, rules);
            if (rule == null) {
                continue;
            }
            GroupLayout g = byId.computeIfAbsent(rule.getGroupId(), id -> groupFor(rule));
            g.getFields().add(p.name());
        }
        // Keep groups in rule order, not in order of first matching parameter.
        for (GroupingRule r : rules) {
            GroupLayout g = byId.get(r.getGroupId());
            if (g != null && !layout.getGroups().contains(g)) {
                layout.getGroups().add(g);
            }
        }
        for (DiscoveredParameter p : params) {
            layout.getFields().put(p.name(), defaultField(p));
        }
        for (GroupLayout g : layout.getGroups()) {
            pairBranchesWithCommits(g, layout);
        }
        return layout;
    }

    /**
     * Places only the parameters that are not yet in any group, keeping every existing group, order, label and help
     * text. Used after the job's parameters change so that one sync never resets the administrator's work.
     */
    public static FormLayout merge(FormLayout existing, List<DiscoveredParameter> params, List<GroupingRule> rules) {
        FormLayout layout = existing.copy();
        Set<String> placed = new HashSet<>();
        for (GroupLayout g : layout.getGroups()) {
            placed.addAll(g.getFields());
        }
        Map<String, GroupLayout> touched = new HashMap<>();
        for (DiscoveredParameter p : params) {
            if (!layout.getFields().containsKey(p.name())) {
                layout.getFields().put(p.name(), defaultField(p));
            }
            if (placed.contains(p.name())) {
                continue;
            }
            GroupingRule rule = firstMatch(p, rules);
            if (rule == null) {
                continue;
            }
            GroupLayout g = layout.findGroup(rule.getGroupId());
            if (g == null) {
                g = groupFor(rule);
                layout.getGroups().add(g);
            }
            g.getFields().add(p.name());
            placed.add(p.name());
            touched.put(g.getId(), g);
        }
        for (GroupLayout g : touched.values()) {
            pairBranchesWithCommits(g, layout);
        }
        return layout;
    }

    static GroupingRule firstMatch(DiscoveredParameter p, List<GroupingRule> rules) {
        for (GroupingRule r : rules) {
            if (r.matches(p)) {
                return r;
            }
        }
        return null;
    }

    private static GroupLayout groupFor(GroupingRule rule) {
        String key = "server.rule." + rule.getGroupId() + ".";
        GroupLayout g = new GroupLayout(rule.getGroupId(), I18n.shipped(key + "title", rule.getTitle()));
        g.setDescription(I18n.shipped(key + "description", rule.getDescription()));
        g.setCollapsed(rule.isCollapsed());
        g.setColumns(rule.getColumns());
        return g;
    }

    private static FieldLayout defaultField(DiscoveredParameter p) {
        FieldLayout f = new FieldLayout();
        if ("text".equals(p.kind())) {
            f.setWidth(FieldLayout.WIDTH_FULL);
        }
        return f;
    }

    /**
     * Moves each {@code X_COMMIT_HASH} (or similar) directly after its {@code X_BRANCH} and links the two so they
     * render on one row. Fields without a partner keep their relative order.
     *
     * <p>The branch always leads the pair, whichever order the two appear in the job: a Jenkinsfile that declares
     * all the commit hashes before all the branches is as common as the other way round, and in both cases the
     * branch is the field people read first. The pair takes the position of whichever of the two came first, so
     * nothing jumps across the group.
     */
    static void pairBranchesWithCommits(GroupLayout g, FormLayout layout) {
        List<String> names = new ArrayList<>(g.getFields());
        Map<String, String> byUpper = new HashMap<>();
        for (String n : names) {
            byUpper.put(n.toUpperCase(Locale.ROOT), n);
        }
        // Resolve the partner of every branch up front, so a commit hash met first can defer to its branch.
        Map<String, String> commitOfBranch = new HashMap<>();
        Map<String, String> branchOfCommit = new HashMap<>();
        for (String n : names) {
            String prefix = stripSuffix(n.toUpperCase(Locale.ROOT), BRANCH_SUFFIXES);
            if (prefix == null) {
                continue;
            }
            for (String cs : COMMIT_SUFFIXES) {
                String partner = byUpper.get(prefix + cs);
                if (partner != null && !partner.equals(n) && !branchOfCommit.containsKey(partner)) {
                    commitOfBranch.put(n, partner);
                    branchOfCommit.put(partner, n);
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (String n : names) {
            if (consumed.contains(n)) {
                continue;
            }
            // Emit the pair branch-first, at the position of whichever half we reached first.
            String branch = branchOfCommit.getOrDefault(n, n);
            String commit = commitOfBranch.get(branch);
            out.add(branch);
            consumed.add(branch);
            if (commit != null) {
                out.add(commit);
                consumed.add(commit);
                layout.getFields()
                        .computeIfAbsent(branch, k -> new FieldLayout())
                        .setLinkWithNext(true);
            }
        }
        g.setFields(out);
    }

    private static String stripSuffix(String name, String[] suffixes) {
        for (String s : suffixes) {
            if (name.endsWith(s) && name.length() > s.length()) {
                return name.substring(0, name.length() - s.length());
            }
        }
        return null;
    }
}
