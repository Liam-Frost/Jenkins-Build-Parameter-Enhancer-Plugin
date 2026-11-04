package io.jenkins.plugins.bpe.model;

import java.io.Serializable;

/**
 * Display-only properties of one parameter. Never affects the parameter's real name, type, value or default.
 */
public class FieldLayout implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String WIDTH_AUTO = "auto";
    public static final String WIDTH_FULL = "full";

    private String label;
    private String shortDescription;
    private String helpText;
    private boolean showHelpIcon = true;
    private String width = WIDTH_AUTO;
    /** Render this field and the next field of the same group as one visual row (e.g. branch + commit). */
    private boolean linkWithNext;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = blankToNull(label);
    }

    public String getShortDescription() {
        return shortDescription;
    }

    public void setShortDescription(String shortDescription) {
        this.shortDescription = blankToNull(shortDescription);
    }

    public String getHelpText() {
        return helpText;
    }

    public void setHelpText(String helpText) {
        this.helpText = blankToNull(helpText);
    }

    public boolean isShowHelpIcon() {
        return showHelpIcon;
    }

    public void setShowHelpIcon(boolean showHelpIcon) {
        this.showHelpIcon = showHelpIcon;
    }

    public String getWidth() {
        return WIDTH_FULL.equals(width) ? WIDTH_FULL : WIDTH_AUTO;
    }

    public void setWidth(String width) {
        this.width = WIDTH_FULL.equals(width) ? WIDTH_FULL : WIDTH_AUTO;
    }

    public boolean isLinkWithNext() {
        return linkWithNext;
    }

    public void setLinkWithNext(boolean linkWithNext) {
        this.linkWithNext = linkWithNext;
    }

    public FieldLayout copy() {
        FieldLayout c = new FieldLayout();
        c.label = label;
        c.shortDescription = shortDescription;
        c.helpText = helpText;
        c.showHelpIcon = showHelpIcon;
        c.width = width;
        c.linkWithNext = linkWithNext;
        return c;
    }

    static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
