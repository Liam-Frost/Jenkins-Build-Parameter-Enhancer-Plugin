package io.jenkins.plugins.bpe;

import java.util.Enumeration;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * User interface text from {@code Strings.properties}, resolved for the current request's locale (the language
 * Jenkins shows the page in). English is the default; {@code Strings_zh.properties} covers Chinese.
 *
 * <p>The JVM's default locale is never used as a fallback: on a controller whose system language is Chinese, a
 * browser asking for English still gets English.
 */
public final class I18n {
    static final String BUNDLE = "io.jenkins.plugins.bpe.Strings";

    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private I18n() {}

    static ResourceBundle bundle(Locale locale) {
        return ResourceBundle.getBundle(BUNDLE, locale, I18n.class.getClassLoader(), NO_FALLBACK);
    }

    /**
     * The request's locale, the same one Jelly uses for {@code ${%...}} text (and that the Locale plugin
     * overrides); English when there is no request (background work, unit tests).
     */
    private static Locale current() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        Locale l = req != null ? req.getLocale() : null;
        return l != null ? l : Locale.ROOT;
    }

    /** The text for {@code key} in the current locale, with {0}, {1}, ... replaced by {@code args}. */
    public static String text(String key, Object... args) {
        return text(current(), key, args);
    }

    static String text(Locale locale, String key, Object... args) {
        String s;
        try {
            s = bundle(locale).getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
        return format(s, args);
    }

    /** Replaces {0}, {1}, ... literally, the same way the frontend does, so both sides read the files alike. */
    static String format(String pattern, Object... args) {
        String s = pattern;
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    /**
     * Translates text that ships in English with the plugin, such as a default rule title: when {@code value} is
     * still the English text of {@code key}, the current locale's text is returned; anything else (an
     * administrator's own wording, or {@code null}) is returned unchanged.
     */
    public static String shipped(String key, String value) {
        if (value == null || !value.equals(text(Locale.ROOT, key))) {
            return value;
        }
        return text(key);
    }

    /** All entries whose key starts with {@code prefix}, for a page that formats its own text. */
    public static JSONObject strings(String prefix) {
        return strings(current(), prefix);
    }

    static JSONObject strings(Locale locale, String prefix) {
        ResourceBundle b = bundle(locale);
        JSONObject o = new JSONObject();
        for (Enumeration<String> keys = b.getKeys(); keys.hasMoreElements(); ) {
            String k = keys.nextElement();
            if (k.startsWith(prefix)) {
                o.put(k, b.getString(k));
            }
        }
        return o;
    }
}
