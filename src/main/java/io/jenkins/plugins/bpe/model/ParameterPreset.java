package io.jenkins.plugins.bpe.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A saved set of parameter values that can be loaded with one click. Global presets are stored in the job's
 * FormLayout and visible to everyone. Personal presets are stored in each user's ParameterPresetUserProperty
 * and visible only to that user.
 */
public class ParameterPreset implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    /** Parameter name → value. */
    private Map<String, String> values = new LinkedHashMap<>();

    private String createdBy;
    private long createdAt;
    private long updatedAt;

    public ParameterPreset() {}

    public ParameterPreset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Test helper: create with name, description and values. */
    public ParameterPreset(String name, String description, String createdBy, Map<String, String> values) {
        this.id = name;
        this.name = name;
        this.description = FieldLayout.blankToNull(description);
        this.createdBy = createdBy;
        this.values = new LinkedHashMap<>(values);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = FieldLayout.blankToNull(description);
    }

    public Map<String, String> getValues() {
        if (values == null) {
            values = new LinkedHashMap<>();
        }
        return values;
    }

    public void setValues(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public ParameterPreset copy() {
        ParameterPreset c = new ParameterPreset(id, name);
        c.description = description;
        c.values = new LinkedHashMap<>(getValues());
        c.createdBy = createdBy;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }
}
