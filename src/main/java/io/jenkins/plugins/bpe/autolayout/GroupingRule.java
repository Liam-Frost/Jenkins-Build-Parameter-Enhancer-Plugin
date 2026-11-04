package io.jenkins.plugins.bpe.autolayout;

import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * One deterministic rule of Auto Generate Layout. A parameter matches when its type is allowed by {@code onlyTypes}
 * (if set) and either its name matches one of {@code patterns} or its type is listed in {@code types}.
 *
 * <p>Patterns are case-insensitive globs ({@code *} = any run of characters, {@code ?} = one character) matched
 * against the whole parameter name. Globs are used instead of regular expressions so that rules edited in the
 * designer cannot cause catastrophic backtracking on the server.
 */
public final class GroupingRule {
    private static final int MAX_PATTERN = 100;

    private final String groupId;
    private final String title;
    private final String description;
    private final boolean collapsed;
    private final int columns;
    private final List<String> patterns;
    private final List<String> types;
    private final List<String> onlyTypes;
    private final transient List<Pattern> compiled;

    public GroupingRule(
            String groupId,
            String title,
            String description,
            boolean collapsed,
            int columns,
            List<String> patterns,
            List<String> types,
            List<String> onlyTypes) {
        this.groupId = groupId;
        this.title = title;
        this.description = description;
        this.collapsed = collapsed;
        this.columns = columns == 1 ? 1 : 2;
        this.patterns = List.copyOf(patterns);
        this.types = List.copyOf(types);
        this.onlyTypes = List.copyOf(onlyTypes);
        List<Pattern> c = new ArrayList<>();
        for (String p : patterns) {
            c.add(globToPattern(p));
        }
        this.compiled = c;
    }

    public boolean matches(DiscoveredParameter p) {
        if (!onlyTypes.isEmpty() && !onlyTypes.contains(p.typeKey())) {
            return false;
        }
        if (types.contains(p.typeKey())) {
            return true;
        }
        for (Pattern pat : compiled) {
            if (pat.matcher(p.name()).matches()) {
                return true;
            }
        }
        return false;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public int getColumns() {
        return columns;
    }

    static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char ch : glob.toCharArray()) {
            switch (ch) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("groupId", groupId);
        o.put("title", title);
        if (description != null) {
            o.put("description", description);
        }
        o.put("collapsed", collapsed);
        o.put("columns", columns);
        o.put("patterns", JSONArray.fromObject(patterns));
        o.put("types", JSONArray.fromObject(types));
        o.put("onlyTypes", JSONArray.fromObject(onlyTypes));
        return o;
    }

    /** Parses a rule list, rejecting malformed input with a message suitable for the UI. */
    public static List<GroupingRule> parseList(JSONArray arr) {
        if (arr.size() > 50) {
            throw new IllegalArgumentException("At most 50 rules are allowed");
        }
        List<GroupingRule> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object raw = arr.get(i);
            if (!(raw instanceof JSONObject)) {
                throw new IllegalArgumentException("Rule " + (i + 1) + " must be an object");
            }
            JSONObject o = (JSONObject) raw;
            String id = o.optString("groupId", "");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Rule " + (i + 1) + ": groupId is missing or invalid");
            }
            String title = o.optString("title", id);
            String desc = o.has("description") ? o.optString("description", null) : null;
            out.add(new GroupingRule(
                    id,
                    title.length() > 120 ? title.substring(0, 120) : title,
                    desc,
                    o.optBoolean("collapsed", false),
                    o.optInt("columns", 2),
                    strings(o.opt("patterns"), "Rule " + (i + 1) + " patterns"),
                    lower(strings(o.opt("types"), "Rule " + (i + 1) + " types")),
                    lower(strings(o.opt("onlyTypes"), "Rule " + (i + 1) + " onlyTypes"))));
        }
        return out;
    }

    private static List<String> strings(Object raw, String what) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof JSONArray)) {
            throw new IllegalArgumentException(what + " must be an array of strings");
        }
        for (Object v : (JSONArray) raw) {
            if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > MAX_PATTERN) {
                throw new IllegalArgumentException(what + " must contain non-empty strings (max " + MAX_PATTERN + ")");
            }
            out.add((String) v);
        }
        if (out.size() > 50) {
            throw new IllegalArgumentException(what + ": at most 50 entries");
        }
        return out;
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
