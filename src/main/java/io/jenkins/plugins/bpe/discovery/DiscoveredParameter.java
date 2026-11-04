package io.jenkins.plugins.bpe.discovery;

import java.util.List;

/**
 * Read-only view of a {@link hudson.model.ParameterDefinition} as currently loaded by the job.
 *
 * @param name real parameter name (never changed by the plugin)
 * @param typeClass fully qualified class of the definition, used for change detection
 * @param typeKey short key: string, text, boolean, choice, password, git or other
 * @param typeLabel human readable type (descriptor display name)
 * @param description raw description as configured (may contain markup)
 * @param descriptionHtml description rendered through Jenkins' configured markup formatter
 * @param descriptionText description with markup removed, for search and summaries
 * @param defaultValue default value as text, or {@code null} if unknown or sensitive
 * @param choices available choices for choice parameters, otherwise {@code null}
 * @param sensitive {@code true} for password-like parameters; defaults are never exposed
 */
public record DiscoveredParameter(
        String name,
        String typeClass,
        String typeKey,
        String typeLabel,
        String description,
        String descriptionHtml,
        String descriptionText,
        String defaultValue,
        List<String> choices,
        boolean sensitive) {

    /** Types whose native markup is the standard {@code f:entry} structure, so labels can be restyled safely. */
    public boolean isManaged() {
        return !"other".equals(typeKey);
    }
}
