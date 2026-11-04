package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Collapsing a group and closing a help panel are both driven by the {@code hidden} attribute, whose only
 * styling comes from the UA stylesheet ({@code display: none}). Any class selector that sets {@code display} —
 * {@code .bpe-group__body} is a grid — has higher specificity and silently wins, leaving the element marked
 * hidden but still painted.
 *
 * <p>HtmlUnit honours the attribute regardless of the cascade, so a page test cannot see this. These assertions
 * read the built stylesheet instead.
 */
class EnhancerStylesheetTest {

    private static final Path CSS = Path.of("src/main/webapp/generated/bpe-enhancer.css");

    private static String css() throws IOException {
        assertTrue(Files.exists(CSS), "run `npm run build` first: " + CSS.toAbsolutePath());
        return Files.readString(CSS, StandardCharsets.UTF_8);
    }

    @Test
    void restoresTheMeaningOfTheHiddenAttribute() throws IOException {
        Matcher m = Pattern.compile("\\[hidden\\][^{]*\\{([^}]*)\\}").matcher(css());
        assertTrue(m.find(), "no rule styles [hidden]; collapsing a group would do nothing visible");
        String body = m.group(1).replaceAll("\\s+", "");
        assertTrue(body.contains("display:none"), "the [hidden] rule must hide: " + body);
        assertTrue(
                body.contains("!important"),
                "without !important the class that sets display on .bpe-group__body wins: " + body);
    }

    @Test
    void theFilterClassAlsoWinsOverLayoutRules() throws IOException {
        Matcher m = Pattern.compile("\\.bpe-hidden[^{]*\\{([^}]*)\\}").matcher(css());
        assertTrue(m.find(), ".bpe-hidden drives search filtering and must exist");
        String body = m.group(1).replaceAll("\\s+", "");
        assertTrue(
                body.contains("display:none") && body.contains("!important"),
                "a filtered-out field must really disappear: " + body);
    }
}
