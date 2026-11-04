package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bpe.autolayout.AutoLayoutGenerator;
import io.jenkins.plugins.bpe.autolayout.GroupingRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class I18nTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");
    private static final Pattern T_CALL = Pattern.compile("\\bt\\(\"([a-zA-Z0-9_.]+)\"");
    private static final Pattern TEXT_CALL = Pattern.compile("I18n\\.(?:text|shipped)\\(\"([a-zA-Z0-9_.]+)\"");

    /** Reads one file on its own: a ResourceBundle would also return the keys inherited from English. */
    private static Properties file(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = I18n.class.getResourceAsStream(name)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void chineseHasExactlyTheEnglishKeys() throws IOException {
        Properties en = file("Strings.properties");
        Properties zh = file("Strings_zh.properties");
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(zh.stringPropertyNames()));
        Set<String> same = new TreeSet<>();
        for (String k : en.stringPropertyNames()) {
            if (en.getProperty(k).equals(zh.getProperty(k))) {
                same.add(k);
            }
        }
        assertTrue(same.isEmpty(), "not translated in Strings_zh.properties: " + same);
    }

    @Test
    void placeholdersMatchBetweenLanguages() {
        ResourceBundle en = I18n.bundle(Locale.ROOT);
        ResourceBundle zh = I18n.bundle(Locale.CHINESE);
        for (String k : en.keySet()) {
            assertEquals(placeholders(en.getString(k)), placeholders(zh.getString(k)), "placeholders of " + k);
        }
    }

    @Test
    void everyKeyUsedInCodeIsDefined() throws IOException {
        Set<String> defined = I18n.bundle(Locale.ROOT).keySet();
        Set<String> used = new TreeSet<>();
        scan(Path.of("src/main/frontend"), T_CALL, used);
        scan(Path.of("src/main/java"), TEXT_CALL, used);
        used.removeIf(k -> k.endsWith("."));
        used.removeIf(defined::contains);
        assertTrue(used.isEmpty(), "keys used but not in Strings.properties: " + used);
    }

    @Test
    void shippedRulesHaveTranslatedTitles() {
        for (GroupingRule r : AutoLayoutGenerator.defaultRules()) {
            String key = "server.rule." + r.getGroupId() + ".";
            assertEquals(r.getTitle(), I18n.text(Locale.ROOT, key + "title"), "English title of " + r.getGroupId());
            if (r.getDescription() != null) {
                assertEquals(r.getDescription(), I18n.text(Locale.ROOT, key + "description"));
            }
        }
    }

    @Test
    void localeFollowsTheRequestNotTheJvm() {
        assertEquals("Other parameters", I18n.text(Locale.ENGLISH, "server.otherGroup"));
        assertEquals("Other parameters", I18n.text(Locale.US, "server.otherGroup"));
        assertEquals("其他参数", I18n.text(Locale.SIMPLIFIED_CHINESE, "server.otherGroup"));
        assertEquals("其他参数", I18n.text(Locale.CHINESE, "server.otherGroup"));
        assertEquals("Invalid rules: x", I18n.text(Locale.ENGLISH, "server.invalidRules", "x"));
        assertEquals("server.no.such.key", I18n.text(Locale.ENGLISH, "server.no.such.key"));
    }

    @Test
    void shippedOnlyTranslatesUnchangedText() {
        // Without a request the locale is English, so shipped text stays as it is.
        assertEquals("Modules", I18n.shipped("server.rule.modules.title", "Modules"));
        assertEquals("Our modules", I18n.shipped("server.rule.modules.title", "Our modules"));
        assertEquals(null, I18n.shipped("server.rule.modules.description", null));
    }

    @Test
    void stringsKeepsOnlyThePrefix() {
        var o = I18n.strings(Locale.CHINESE, "build.");
        assertTrue(o.size() > 10);
        assertTrue(o.keySet().stream().allMatch(k -> ((String) k).startsWith("build.")));
        assertEquals("全部展开", o.getString("build.expandAll"));
    }

    private static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static void scan(Path root, Pattern p, Set<String> out) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = p.matcher(s);
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
    }
}
