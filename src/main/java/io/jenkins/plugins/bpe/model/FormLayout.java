package io.jenkins.plugins.bpe.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Display layout of a job's build form: ordered groups, per-field display properties, and a snapshot of the
 * parameters (name to type class) that existed when the layout was last saved, used for change detection.
 */
public class FormLayout implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private String otherGroupTitle;
    private List<GroupLayout> groups = new ArrayList<>();
    private Map<String, FieldLayout> fields = new LinkedHashMap<>();
    private Map<String, String> knownParameters = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getOtherGroupTitle() {
        return otherGroupTitle;
    }

    public void setOtherGroupTitle(String otherGroupTitle) {
        this.otherGroupTitle = FieldLayout.blankToNull(otherGroupTitle);
    }

    public List<GroupLayout> getGroups() {
        if (groups == null) {
            groups = new ArrayList<>();
        }
        return groups;
    }

    public Map<String, FieldLayout> getFields() {
        if (fields == null) {
            fields = new LinkedHashMap<>();
        }
        return fields;
    }

    public FieldLayout getField(String name) {
        return getFields().get(name);
    }

    public Map<String, String> getKnownParameters() {
        if (knownParameters == null) {
            knownParameters = new LinkedHashMap<>();
        }
        return knownParameters;
    }

    public void setKnownParameters(Map<String, String> knownParameters) {
        this.knownParameters = new LinkedHashMap<>(knownParameters);
    }

    /** @return the group containing the named parameter, or {@code null} if it is unassigned. */
    public GroupLayout findGroupOf(String name) {
        for (GroupLayout g : getGroups()) {
            if (g.getFields().contains(name)) {
                return g;
            }
        }
        return null;
    }

    public GroupLayout findGroup(String id) {
        for (GroupLayout g : getGroups()) {
            if (g.getId().equals(id)) {
                return g;
            }
        }
        return null;
    }

    public FormLayout copy() {
        FormLayout c = new FormLayout();
        c.schemaVersion = schemaVersion;
        c.otherGroupTitle = otherGroupTitle;
        for (GroupLayout g : getGroups()) {
            c.getGroups().add(g.copy());
        }
        for (Map.Entry<String, FieldLayout> e : getFields().entrySet()) {
            c.getFields().put(e.getKey(), e.getValue().copy());
        }
        c.knownParameters = new LinkedHashMap<>(getKnownParameters());
        return c;
    }
}
