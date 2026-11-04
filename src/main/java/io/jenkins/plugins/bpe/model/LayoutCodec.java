package io.jenkins.plugins.bpe.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

/**
 * Converts {@link FormLayout} to and from the JSON exchanged with the designer, and validates untrusted input.
 * The JSON shape is documented in README.md ("Layout schema").
 */
public final class LayoutCodec {
    public static final int MAX_GROUPS = 50;
    public static final int MAX_FIELDS = 500;
    public static final int MAX_TITLE = 120;
    public static final int MAX_SHORT = 300;
    public static final int MAX_HELP = 10_000;
    public static final int MAX_NAME = 200;
    private static final Pattern GROUP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private LayoutCodec() {}

    public static JSONObject toJson(FormLayout layout) {
        JSONObject o = new JSONObject();
        o.put("schemaVersion", layout.getSchemaVersion());
        o.put("otherGroupTitle", nullable(layout.getOtherGroupTitle()));
        JSONArray groups = new JSONArray();
        for (GroupLayout g : layout.getGroups()) {
            JSONObject go = new JSONObject();
            go.put("id", g.getId());
            go.put("title", g.getTitle());
            go.put("description", nullable(g.getDescription()));
            go.put("collapsed", g.isCollapsed());
            go.put("columns", g.getColumns());
            go.put("parentId", nullable(g.getParentId()));
            go.put("position", g.getPosition() == null ? JSONNull.getInstance() : g.getPosition());
            go.put("fields", JSONArray.fromObject(g.getFields()));
            groups.add(go);
        }
        o.put("groups", groups);
        JSONObject fields = new JSONObject();
        for (Map.Entry<String, FieldLayout> e : layout.getFields().entrySet()) {
            FieldLayout f = e.getValue();
            JSONObject fo = new JSONObject();
            fo.put("label", nullable(f.getLabel()));
            fo.put("shortDescription", nullable(f.getShortDescription()));
            fo.put("helpText", nullable(f.getHelpText()));
            fo.put("showHelpIcon", f.isShowHelpIcon());
            fo.put("width", f.getWidth());
            fo.put("linkWithNext", f.isLinkWithNext());
            fields.put(e.getKey(), fo);
        }
        o.put("fields", fields);
        JSONObject known = new JSONObject();
        known.putAll(layout.getKnownParameters());
        o.put("knownParameters", known);
        return o;
    }

    /**
     * Parses and validates a layout received from a client.
     *
     * @throws LayoutValidationException listing every problem found
     */
    public static FormLayout fromJson(JSONObject o) throws LayoutValidationException {
        List<String> errors = new ArrayList<>();
        FormLayout layout = new FormLayout();
        int version = o.optInt("schemaVersion", FormLayout.SCHEMA_VERSION);
        if (version != FormLayout.SCHEMA_VERSION) {
            errors.add("Unsupported schemaVersion " + version + " (expected " + FormLayout.SCHEMA_VERSION + ")");
        }
        layout.setOtherGroupTitle(limited(optString(o, "otherGroupTitle"), MAX_TITLE, "otherGroupTitle", errors));

        Object groupsRaw = o.opt("groups");
        JSONArray groups = groupsRaw instanceof JSONArray ? (JSONArray) groupsRaw : new JSONArray();
        if (groupsRaw != null && !(groupsRaw instanceof JSONArray)) {
            errors.add("groups must be an array");
        }
        if (groups.size() > MAX_GROUPS) {
            errors.add("Too many groups (max " + MAX_GROUPS + ")");
        }
        Set<String> groupIds = new HashSet<>();
        Set<String> placed = new HashSet<>();
        for (int i = 0; i < groups.size() && i < MAX_GROUPS; i++) {
            Object raw = groups.get(i);
            if (!(raw instanceof JSONObject)) {
                errors.add("groups[" + i + "] must be an object");
                continue;
            }
            JSONObject go = (JSONObject) raw;
            String id = optString(go, "id");
            if (id == null || !GROUP_ID.matcher(id).matches()) {
                errors.add("groups[" + i + "].id is missing or invalid (letters, digits, '_' and '-', max 64)");
                continue;
            }
            if (!groupIds.add(id)) {
                errors.add("Duplicate group id '" + id + "'");
                continue;
            }
            GroupLayout g = new GroupLayout(id, null);
            String title = limited(optString(go, "title"), MAX_TITLE, "groups[" + id + "].title", errors);
            g.setTitle(title == null ? id : title);
            g.setDescription(
                    limited(optString(go, "description"), MAX_SHORT, "groups[" + id + "].description", errors));
            g.setCollapsed(go.optBoolean("collapsed", false));
            g.setColumns(go.optInt("columns", 2));
            String parentId = optString(go, "parentId");
            if (parentId != null && !GROUP_ID.matcher(parentId).matches()) {
                errors.add("groups[" + id + "].parentId is invalid");
                parentId = null;
            }
            g.setParentId(parentId);
            Object pos = go.opt("position");
            if (pos instanceof Number && parentId != null) {
                int n = ((Number) pos).intValue();
                if (n < 0 || n > MAX_FIELDS) {
                    errors.add("groups[" + id + "].position is out of range");
                } else {
                    g.setPosition(n);
                }
            } else if (pos != null && !(pos instanceof JSONNull) && !(pos instanceof Number)) {
                errors.add("groups[" + id + "].position must be a number");
            }
            List<String> names = new ArrayList<>();
            Object fieldsRaw = go.opt("fields");
            if (fieldsRaw instanceof JSONArray) {
                for (Object n : (JSONArray) fieldsRaw) {
                    if (!(n instanceof String) || ((String) n).isEmpty() || ((String) n).length() > MAX_NAME) {
                        errors.add("groups[" + id + "].fields contains an invalid parameter name");
                        continue;
                    }
                    String name = (String) n;
                    if (!placed.add(name)) {
                        errors.add("Parameter '" + name + "' is placed more than once");
                        continue;
                    }
                    names.add(name);
                }
            } else if (fieldsRaw != null) {
                errors.add("groups[" + id + "].fields must be an array");
            }
            g.setFields(names);
            layout.getGroups().add(g);
        }

        // Validate nesting: parentId must point to a top-level group, and no deeply nested groups.
        Map<String, GroupLayout> byId = new java.util.HashMap<>();
        for (GroupLayout g : layout.getGroups()) {
            byId.put(g.getId(), g);
        }
        for (GroupLayout g : layout.getGroups()) {
            String p = g.getParentId();
            if (p != null) {
                if (p.equals(g.getId())) {
                    errors.add("Group '" + g.getId() + "' cannot be its own parent");
                } else if (!byId.containsKey(p)) {
                    errors.add("Group '" + g.getId() + "' has unknown parent '" + p + "'");
                } else {
                    GroupLayout parent = byId.get(p);
                    if (parent.getParentId() != null) {
                        errors.add("Group '" + g.getId() + "' nests under '" + p
                                + "', which is itself nested (max 1 level)");
                    }
                }
            }
        }

        if (placed.size() > MAX_FIELDS) {
            errors.add("Too many fields (max " + MAX_FIELDS + ")");
        }

        Object fieldsRaw = o.opt("fields");
        if (fieldsRaw instanceof JSONObject) {
            JSONObject fields = (JSONObject) fieldsRaw;
            if (fields.size() > MAX_FIELDS) {
                errors.add("Too many field settings (max " + MAX_FIELDS + ")");
            } else {
                for (Object k : fields.keySet()) {
                    String name = String.valueOf(k);
                    Object v = fields.get(name);
                    if (name.isEmpty() || name.length() > MAX_NAME || !(v instanceof JSONObject)) {
                        errors.add("Invalid field settings for '" + name + "'");
                        continue;
                    }
                    JSONObject fo = (JSONObject) v;
                    FieldLayout f = new FieldLayout();
                    f.setLabel(limited(optString(fo, "label"), MAX_TITLE, name + ".label", errors));
                    f.setShortDescription(
                            limited(optString(fo, "shortDescription"), MAX_SHORT, name + ".shortDescription", errors));
                    f.setHelpText(limited(optString(fo, "helpText"), MAX_HELP, name + ".helpText", errors));
                    f.setShowHelpIcon(fo.optBoolean("showHelpIcon", true));
                    f.setWidth(optString(fo, "width"));
                    f.setLinkWithNext(fo.optBoolean("linkWithNext", false));
                    layout.getFields().put(name, f);
                }
            }
        } else if (fieldsRaw != null) {
            errors.add("fields must be an object");
        }
        // knownParameters is always recomputed on the server; client-supplied values are ignored.
        if (!errors.isEmpty()) {
            throw new LayoutValidationException(errors);
        }
        return layout;
    }

    private static Object nullable(String s) {
        return s == null ? net.sf.json.JSONNull.getInstance() : s;
    }

    private static String optString(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v instanceof net.sf.json.JSONNull) {
            return null;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    private static String limited(String s, int max, String what, List<String> errors) {
        if (s != null && s.length() > max) {
            errors.add(what + " is longer than " + max + " characters");
            return s.substring(0, max);
        }
        return s;
    }
}
