package io.jenkins.plugins.bpe;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.user.ParameterPresetUserProperty;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Carries the form design over when a job is copied. The design itself needs no handling on rename or move: it lives
 * inside the job's root directory, which Jenkins moves with the job. Personal presets are keyed by the job's full
 * name on each user, so those are re-keyed on rename/move and dropped on delete.
 */
@Extension
public class CopyListener extends ItemListener {
    private static final Logger LOGGER = Logger.getLogger(CopyListener.class.getName());

    @Override
    public void onCopied(Item src, Item item) {
        if (!(src instanceof Job) || !(item instanceof Job)) {
            return;
        }
        Job<?, ?> from = (Job<?, ?>) src;
        Job<?, ?> to = (Job<?, ?>) item;
        if (!FormDesignStore.exists(from)) {
            return;
        }
        try {
            FormDesign design = FormDesignStore.load(from);
            FormDesignStore.save(to, design);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not copy the build form design to " + to.getFullName(), e);
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        if (item instanceof Job) {
            forEachPresetOwner(p -> p.rename(oldFullName, newFullName));
        }
    }

    @Override
    public void onDeleted(Item item) {
        if (item instanceof Job) {
            forEachPresetOwner(p -> p.forget(item.getFullName()));
        }
    }

    /** Applies the change to every user holding presets, saving only those it actually changed. */
    private static void forEachPresetOwner(java.util.function.Predicate<ParameterPresetUserProperty> change) {
        for (User u : User.getAll()) {
            ParameterPresetUserProperty p = u.getProperty(ParameterPresetUserProperty.class);
            if (p != null && change.test(p)) {
                try {
                    u.save();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not update the presets of user " + u.getId(), e);
                }
            }
        }
    }
}
