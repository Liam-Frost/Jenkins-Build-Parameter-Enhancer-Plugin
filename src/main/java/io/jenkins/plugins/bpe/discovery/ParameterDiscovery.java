package io.jenkins.plugins.bpe.discovery;

import hudson.markup.MarkupFormatter;
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Reads the parameter definitions currently loaded by a job. Works on the real {@link ParameterDefinition} objects;
 * it never parses HTML or a Jenkinsfile.
 */
public final class ParameterDiscovery {
    private static final Logger LOGGER = Logger.getLogger(ParameterDiscovery.class.getName());
    static final String GIT_PARAMETER_CLASS = "net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition";

    private ParameterDiscovery() {}

    public static List<ParameterDefinition> definitions(Job<?, ?> job) {
        ParametersDefinitionProperty pdp = job.getProperty(ParametersDefinitionProperty.class);
        if (pdp == null) {
            return Collections.emptyList();
        }
        return pdp.getParameterDefinitions();
    }

    public static List<DiscoveredParameter> discover(Job<?, ?> job) {
        List<DiscoveredParameter> out = new ArrayList<>();
        for (ParameterDefinition d : definitions(job)) {
            out.add(describe(d));
        }
        return out;
    }

    public static DiscoveredParameter describe(ParameterDefinition d) {
        String typeKey = typeKey(d);
        boolean sensitive = isSensitive(d);
        String raw = d.getDescription();
        String html = formatMarkup(raw);
        List<String> choices = null;
        if (d instanceof ChoiceParameterDefinition) {
            choices = List.copyOf(((ChoiceParameterDefinition) d).getChoices());
        }
        String typeLabel;
        try {
            typeLabel = d.getDescriptor().getDisplayName();
        } catch (RuntimeException e) {
            typeLabel = d.getClass().getSimpleName();
        }
        return new DiscoveredParameter(
                d.getName(),
                d.getClass().getName(),
                typeKey,
                typeLabel,
                raw,
                html,
                toPlainText(html),
                sensitive ? null : safeDefault(d),
                choices,
                sensitive);
    }

    public static String typeKey(ParameterDefinition d) {
        if (d instanceof PasswordParameterDefinition) {
            return "password";
        }
        if (d instanceof BooleanParameterDefinition) {
            return "boolean";
        }
        if (d instanceof ChoiceParameterDefinition) {
            return "choice";
        }
        // TextParameterDefinition extends StringParameterDefinition, so check it first.
        if (d instanceof TextParameterDefinition) {
            return "text";
        }
        if (d instanceof StringParameterDefinition) {
            return "string";
        }
        if (GIT_PARAMETER_CLASS.equals(d.getClass().getName())) {
            return "git";
        }
        return "other";
    }

    static boolean isSensitive(ParameterDefinition d) {
        if (d instanceof PasswordParameterDefinition) {
            return true;
        }
        String cls = d.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return cls.contains("password") || cls.contains("secret");
    }

    /**
     * Default value for display in the designer. Only read from types known to compute it without side effects;
     * third-party types (which may run scripts or contact an SCM) are skipped.
     */
    static String safeDefault(ParameterDefinition d) {
        try {
            if (d instanceof BooleanParameterDefinition) {
                return String.valueOf(((BooleanParameterDefinition) d).isDefaultValue());
            }
            if (d instanceof ChoiceParameterDefinition) {
                List<String> c = ((ChoiceParameterDefinition) d).getChoices();
                return c.isEmpty() ? null : c.get(0);
            }
            if (d instanceof StringParameterDefinition) {
                return ((StringParameterDefinition) d).getDefaultValue();
            }
            if (GIT_PARAMETER_CLASS.equals(d.getClass().getName())) {
                Object v = d.getClass().getMethod("getDefaultValue").invoke(d);
                return v == null ? null : String.valueOf(v);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read default of " + d.getName(), e);
        }
        return null;
    }

    /** Renders text through the configured markup formatter, exactly as Jenkins renders parameter descriptions. */
    public static String formatMarkup(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            MarkupFormatter f = Jenkins.get().getMarkupFormatter();
            StringWriter w = new StringWriter();
            f.translate(text, w);
            return w.toString();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Markup formatting failed", e);
            return hudson.Util.escape(text);
        }
    }

    /** Strips tags and decodes the common entities; the result is only ever inserted as text, never as HTML. */
    public static String toPlainText(String html) {
        if (html == null) {
            return null;
        }
        // Decode entities first, so escaped tags become real tags that the regex can strip.
        String s = html.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        s = s.replaceAll("<[^>]*>", "");
        return s.strip();
    }
}
