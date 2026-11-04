package io.jenkins.plugins.bpe.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the plugin stores for one job: the enable switch, the working draft, the published layout that end
 * users see, and a short history of previously published layouts for rollback.
 */
public class FormDesign {
    public static final int MAX_HISTORY = 10;

    private boolean enabled;
    private FormLayout draft;
    private Revision published;
    private List<Revision> history = new ArrayList<>();
    private long draftSavedAt;
    private String draftSavedBy;
    /** Global presets visible to everyone. Stored outside the layout so they survive rollback. */
    private List<ParameterPreset> globalPresets = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public FormLayout getDraft() {
        return draft;
    }

    public long getDraftSavedAt() {
        return draftSavedAt;
    }

    public String getDraftSavedBy() {
        return draftSavedBy;
    }

    public void saveDraft(FormLayout layout, String user) {
        this.draft = layout;
        this.draftSavedAt = System.currentTimeMillis();
        this.draftSavedBy = user;
    }

    public void discardDraft() {
        this.draft = null;
        this.draftSavedAt = 0;
        this.draftSavedBy = null;
    }

    public Revision getPublished() {
        return published;
    }

    public FormLayout getPublishedLayout() {
        return published == null ? null : published.getLayout();
    }

    /** The layout the designer should open: the draft if there is one, otherwise the published layout. */
    public FormLayout getWorkingLayout() {
        return draft != null ? draft : getPublishedLayout();
    }

    public List<Revision> getHistory() {
        if (history == null) {
            history = new ArrayList<>();
        }
        return history;
    }

    /** Publishes the layout, pushes the previously published revision onto the history, and clears the draft. */
    public void publish(FormLayout layout, String user) {
        if (published != null) {
            getHistory().add(0, published);
            while (getHistory().size() > MAX_HISTORY) {
                getHistory().remove(getHistory().size() - 1);
            }
        }
        published = new Revision(layout, System.currentTimeMillis(), user);
        discardDraft();
    }

    /**
     * Restores the most recent previously published revision. The current published revision is dropped.
     *
     * @return {@code false} if there is nothing to roll back to
     */
    public boolean rollback() {
        if (getHistory().isEmpty()) {
            return false;
        }
        published = getHistory().remove(0);
        discardDraft();
        return true;
    }

    public List<ParameterPreset> getGlobalPresets() {
        if (globalPresets == null) {
            globalPresets = new ArrayList<>();
        }
        return globalPresets;
    }

    public static class Revision {
        private final FormLayout layout;
        private final long publishedAt;
        private final String publishedBy;

        public Revision(FormLayout layout, long publishedAt, String publishedBy) {
            this.layout = layout;
            this.publishedAt = publishedAt;
            this.publishedBy = publishedBy;
        }

        public FormLayout getLayout() {
            return layout;
        }

        public long getPublishedAt() {
            return publishedAt;
        }

        public String getPublishedBy() {
            return publishedBy;
        }
    }
}
