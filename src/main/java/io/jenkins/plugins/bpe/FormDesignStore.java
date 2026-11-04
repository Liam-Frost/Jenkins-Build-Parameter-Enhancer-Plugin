package io.jenkins.plugins.bpe;

import hudson.XmlFile;
import hudson.model.Job;
import hudson.util.XStream2;
import io.jenkins.plugins.bpe.model.FieldLayout;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import io.jenkins.plugins.bpe.model.ParameterPreset;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists a job's {@link FormDesign} as {@code build-parameter-enhancer.xml} in the job's root directory.
 *
 * <p>The design is deliberately not a {@code JobProperty}: a Pipeline's {@code properties([...])} step replaces the
 * job's properties on every run, which would erase the layout. A file next to {@code config.xml} is untouched by
 * Pipeline runs and moves with the job on rename. {@link CopyListener} copies it when a job is copied.
 */
public final class FormDesignStore {
    private static final Logger LOGGER = Logger.getLogger(FormDesignStore.class.getName());
    public static final String FILE_NAME = "build-parameter-enhancer.xml";

    private static final XStream2 XSTREAM = new XStream2();

    static {
        XSTREAM.alias("build-parameter-enhancer", FormDesign.class);
        XSTREAM.alias("revision", FormDesign.Revision.class);
        XSTREAM.alias("layout", FormLayout.class);
        XSTREAM.alias("group", GroupLayout.class);
        XSTREAM.alias("field", FieldLayout.class);
        XSTREAM.alias("preset", ParameterPreset.class);
    }

    private FormDesignStore() {}

    static XmlFile file(Job<?, ?> job) {
        return new XmlFile(XSTREAM, new File(job.getRootDir(), FILE_NAME));
    }

    /** Loads the design, returning an empty (disabled) design if none was saved or the file is unreadable. */
    public static synchronized FormDesign load(Job<?, ?> job) {
        XmlFile f = file(job);
        if (f.exists()) {
            try {
                Object o = f.read();
                if (o instanceof FormDesign) {
                    return (FormDesign) o;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not read " + f + "; the native build form will be used", e);
            }
        }
        return new FormDesign();
    }

    public static synchronized void save(Job<?, ?> job, FormDesign design) throws IOException {
        file(job).write(design);
    }

    public static boolean exists(Job<?, ?> job) {
        return file(job).exists();
    }
}
