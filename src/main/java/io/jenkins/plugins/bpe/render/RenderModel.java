package io.jenkins.plugins.bpe.render;

import io.jenkins.plugins.bpe.I18n;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.discovery.ParameterDiscovery;
import io.jenkins.plugins.bpe.model.FieldLayout;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Turns a layout plus the job's current parameters into the JSON the build page script uses. Guarantees that
 * every current parameter appears exactly once: parameters not placed in a group go to a fallback group at the
 * end, and names that no longer exist are dropped.
 *
 * <p>Only display data is included. Default values are not, so nothing sensitive can leak through this payload.
 */
public final class RenderModel {
    public static final String OTHER_GROUP_ID = "__other";
    /** Title of the fallback group when the layout does not set one, in the page's language. */
    public static String defaultOtherTitle() {
        return I18n.text("server.otherGroup");
    }

    private RenderModel() {}

    public static JSONObject build(FormLayout layout, List<DiscoveredParameter> params, boolean preview) {
        Map<String, DiscoveredParameter> byName = new LinkedHashMap<>();
        for (DiscoveredParameter p : params) {
            byName.put(p.name(), p);
        }
        Set<String> placed = new HashSet<>();
        JSONArray groups = new JSONArray();
        for (GroupLayout g : layout.getGroups()) {
            // Only include top-level groups here; nested groups are emitted within their parent.
            if (g.getParentId() == null) {
                JSONObject go = buildGroupTree(g, layout, byName, placed);
                if (go != null) {
                    groups.add(go);
                }
            }
        }
        List<String> rest = new ArrayList<>();
        for (String n : byName.keySet()) {
            if (!placed.contains(n)) {
                rest.add(n);
            }
        }
        if (!rest.isEmpty()) {
            String title = layout.getOtherGroupTitle() != null ? layout.getOtherGroupTitle() : defaultOtherTitle();
            groups.add(group(OTHER_GROUP_ID, title, null, false, 2, rest, layout, byName));
        }
        JSONObject o = new JSONObject();
        o.put("version", 1);
        o.put("preview", preview);
        o.put("parameterNames", JSONArray.fromObject(new ArrayList<>(byName.keySet())));
        o.put("groups", groups);
        return o;
    }

    /**
     * Builds a group and recursively includes its children. A group is emitted if it has fields or if it has
     * children with fields.
     */
    private static JSONObject buildGroupTree(
            GroupLayout g, FormLayout layout, Map<String, DiscoveredParameter> byName, Set<String> placed) {
        // Own fields
        List<String> names = new ArrayList<>();
        for (String n : g.getFields()) {
            if (byName.containsKey(n) && placed.add(n)) {
                names.add(n);
            }
        }

        // Child groups, in display order. "position" counts the parent's layout fields shown before the child; it is
        // turned into an index among the fields actually rendered, so a removed parameter does not shift a sub-group.
        JSONArray children = new JSONArray();
        for (GroupLayout child : layout.getGroups()) {
            if (g.getId().equals(child.getParentId())) {
                JSONObject childJson = buildGroupTree(child, layout, byName, placed);
                if (childJson != null) {
                    Integer pos = child.getPosition();
                    if (pos != null) {
                        int shown = 0;
                        int end = Math.min(pos, g.getFields().size());
                        for (int i = 0; i < end; i++) {
                            if (names.contains(g.getFields().get(i))) {
                                shown++;
                            }
                        }
                        childJson.put("position", shown);
                    }
                    children.add(childJson);
                }
            }
        }

        // Emit if this group or any child has content
        if (names.isEmpty() && children.isEmpty()) {
            return null;
        }

        JSONObject go = group(
                g.getId(), g.getTitle(), g.getDescription(), g.isCollapsed(), g.getColumns(), names, layout, byName);
        if (g.getParentId() != null) {
            go.put("parentId", g.getParentId());
        }
        if (!children.isEmpty()) {
            go.put("children", children);
        }
        return go;
    }

    private static JSONObject group(
            String id,
            String title,
            String description,
            boolean collapsed,
            int columns,
            List<String> names,
            FormLayout layout,
            Map<String, DiscoveredParameter> byName) {
        JSONObject go = new JSONObject();
        go.put("id", id);
        go.put("title", title == null ? id : title);
        go.put("description", description == null ? "" : description);
        go.put("collapsed", collapsed);
        go.put("columns", columns);
        JSONArray fields = new JSONArray();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            DiscoveredParameter p = byName.get(n);
            FieldLayout f = layout.getField(n);
            if (f == null) {
                f = new FieldLayout();
            }
            JSONObject fo = new JSONObject();
            fo.put("name", n);
            fo.put("type", p.typeKey());
            fo.put("managed", p.isManaged());
            fo.put("label", f.getLabel() == null ? n : f.getLabel());
            fo.put("labelOverridden", f.getLabel() != null && !f.getLabel().equals(n));
            String summary = f.getShortDescription();
            boolean summaryFromDescription = false;
            if (summary == null && p.descriptionText() != null) {
                summary = firstLine(p.descriptionText());
                summaryFromDescription = true;
            }
            fo.put("summary", summary == null ? "" : summary);
            fo.put("summaryFromDescription", summaryFromDescription);
            // helpHtml is produced by Jenkins' configured markup formatter, the same path core uses for
            // descriptions, so it is no less safe than the native page.
            String helpHtml = ParameterDiscovery.formatMarkup(f.getHelpText());
            fo.put("helpHtml", helpHtml == null ? "" : helpHtml);
            fo.put(
                    "hasDescription",
                    p.descriptionText() != null && !p.descriptionText().isEmpty());
            fo.put("showHelpIcon", f.isShowHelpIcon());
            fo.put("width", f.getWidth());
            // linkWithNext only makes sense when there is a next field in the same group.
            fo.put("linkWithNext", f.isLinkWithNext() && i + 1 < names.size());
            fo.put("search", searchText(n, f, p));
            fields.add(fo);
        }
        go.put("fields", fields);
        return go;
    }

    private static String firstLine(String text) {
        String t = text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }

    private static String searchText(String name, FieldLayout f, DiscoveredParameter p) {
        StringBuilder sb = new StringBuilder(name);
        for (String s : new String[] {f.getLabel(), f.getShortDescription(), f.getHelpText(), p.descriptionText()}) {
            if (s != null) {
                sb.append('\n').append(s);
            }
        }
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
