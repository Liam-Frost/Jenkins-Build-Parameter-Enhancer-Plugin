package io.jenkins.plugins.bpe.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** A titled, collapsible group of parameters on the build form. */
public class GroupLayout implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String title;
    private String description;
    private boolean collapsed;
    private int columns = 2;
    /** Parameter names in display order. */
    private List<String> fields = new ArrayList<>();
    /** The group this one is nested in, or null for a top-level group. Only one level of nesting is allowed. */
    private String parentId;
    /**
     * For a nested group: how many of the parent's fields are shown before it. Null shows it after all of them, which
     * is also how layouts saved before this setting existed are rendered.
     */
    private Integer position;

    public GroupLayout() {}

    public GroupLayout(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = FieldLayout.blankToNull(description);
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public int getColumns() {
        return columns == 1 ? 1 : 2;
    }

    public void setColumns(int columns) {
        this.columns = columns == 1 ? 1 : 2;
    }

    public List<String> getFields() {
        if (fields == null) {
            fields = new ArrayList<>();
        }
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = new ArrayList<>(fields);
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = FieldLayout.blankToNull(parentId);
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position == null || position < 0 ? null : position;
    }

    /** Computes the nesting level: 0 for top-level groups, 1 for nested. */
    public int getLevel() {
        return parentId == null ? 0 : 1;
    }

    public GroupLayout copy() {
        GroupLayout c = new GroupLayout(id, title);
        c.description = description;
        c.collapsed = collapsed;
        c.columns = columns;
        c.fields = new ArrayList<>(getFields());
        c.position = position;
        c.parentId = parentId;
        return c;
    }
}
