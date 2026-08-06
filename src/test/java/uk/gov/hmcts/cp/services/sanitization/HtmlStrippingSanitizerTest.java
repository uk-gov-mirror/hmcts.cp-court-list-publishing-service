package uk.gov.hmcts.cp.services.sanitization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlStrippingSanitizerTest {

    /**
     * Mirrors the strictest anti-HTML rule applied by the downstream schemas:
     * no substring may match {@code <[^>]+>}, in raw or entity-encoded form.
     */
    private static final Pattern FORBIDDEN_RAW = Pattern.compile("<[^>]+>");
    private static final Pattern FORBIDDEN_ENTITY = Pattern.compile("&lt;[^&]*&gt;");

    private final HtmlStrippingSanitizer sanitizer = new HtmlStrippingSanitizer();

    @Test
    void returnsNullWhenInputIsNull() {
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    @Test
    void returnsNullWhenInputIsEmpty() {
        // Empty in -> null out. Required-field presence is enforced by
        // DocumentSanitizer after the walk; this sanitiser's job is
        // only to signal "nothing meaningful left".
        assertThat(sanitizer.sanitize("")).isNull();
    }

    @Test
    void returnsNullWhenInputIsWhitespaceOnly() {
        assertThat(sanitizer.sanitize("   ")).isNull();
        assertThat(sanitizer.sanitize("\n\t ")).isNull();
    }

    @Test
    void trimsSurroundingWhitespaceWhenNoHtmlPresent() {
        assertThat(sanitizer.sanitize("  hello  ")).isEqualTo("hello");
        assertThat(sanitizer.sanitize("\nhello\t")).isEqualTo("hello");
    }

    @Test
    void collapsesInternalWhitespaceRuns() {
        assertThat(sanitizer.sanitize("foo   bar")).isEqualTo("foo bar");
    }

    @Test
    void returnsSameInstanceWhenNoTagLikeContent() {
        String input = "Contrary to section 40CA(1) and (4) of the Prison Act 1952.";
        assertThat(sanitizer.sanitize(input)).isSameAs(input);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // raw HTML tags
            "Contrary to s.40.<br /><br /> | Contrary to s.40.",
            "<p>hello</p>                  | hello",
            "before<br/>after              | before after",
            "<div class=\"x\">text</div>   | text",
            // entity-encoded forms (schema patterns 6/7 forbid closing/self-closing)
            "&lt;br/&gt;hello              | hello",
            "before&lt;br/&gt;after        | before after",
            "&lt;/p&gt;trailing            | trailing",
            // mixed raw and entity-encoded
            "<br/>middle&lt;/p&gt;         | middle",
    })
    void stripsAllRawAndEntityEncodedTags(String input, String expected) {
        assertThat(sanitizer.sanitize(input)).isEqualTo(expected);
    }

    @Test
    void collapsesWhitespaceAcrossNewlinesAfterStripping() {
        String input = "line1<br/>\nline2";
        assertThat(sanitizer.sanitize(input)).isEqualTo("line1 line2");
    }

    @Test
    void returnsNullWhenInputIsEntirelyTagLike() {
        // Entirely-HTML inputs collapse to null; the walker decides whether
        // to drop the field (optional) or substitute "" (required).
        assertThat(sanitizer.sanitize("<br />")).isNull();
        assertThat(sanitizer.sanitize("<br/><br/>")).isNull();
        assertThat(sanitizer.sanitize("&lt;br/&gt;")).isNull();
        assertThat(sanitizer.sanitize("<p></p>")).isNull();
    }

    @Test
    void handlesRealisticOffenceWordingWithPlaceholderAndSurroundingNewlines() {
        // Realistic offence-wording shape: a placeholder ("<the quantity>") inside
        // a long narrative, plus surrounding newlines from upstream formatting. The
        // placeholder is stripped (same lossy trade-off as
        // dropsAngleBracketedPlaceholderText); newlines are trimmed and the gap
        // around the dropped placeholder is collapsed to a single space.
        // The non-ASCII '£' symbol and the '&/or' fragment must survive untouched.
        String input = "\n"
                + "MD71530 Possess a controlled drug of Class B - Cannabis / Cannabis Resin "
                + "on 14/04/2026 at greater manchester had in your possession <the quantity> "
                + "of cannabis, a controlled drug of class B in contravention of section 5(1) "
                + "of the Misuse of Drugs Act 1971 Contrary to section 5(2) of and Schedule 4 "
                + "to the Misuse of Drugs Act 1971. (Hearing 1) MAX PENALTY: EW:3M &/or £2500"
                + "\n";

        String expected = "MD71530 Possess a controlled drug of Class B - Cannabis / Cannabis Resin "
                + "on 14/04/2026 at greater manchester had in your possession of cannabis, "
                + "a controlled drug of class B in contravention of section 5(1) of the Misuse "
                + "of Drugs Act 1971 Contrary to section 5(2) of and Schedule 4 to the Misuse "
                + "of Drugs Act 1971. (Hearing 1) MAX PENALTY: EW:3M &/or £2500";

        assertThat(sanitizer.sanitize(input)).isEqualTo(expected);
    }

    @Test
    void dropsAngleBracketedPlaceholderText() {
        // Real-world ambiguity: a human-authored string can use <...> as literal
        // placeholder text (here "<amount due>"), but the schema's anti-HTML rule
        // cannot distinguish that from an actual HTML tag — both match `<[^>]+>`.
        // We must strip it to pass schema validation; the cost is silent data loss
        // for human-authored angle-bracket templates of this shape.
        String input = "she is due to pay <amount due> by september 2027";
        assertThat(sanitizer.sanitize(input)).isEqualTo("she is due to pay by september 2027");
    }

    @Test
    void leavesBareLessThanIntactWhenNoMatchingGreaterThan() {
        // The schema regex requires a closing '>' to consider a substring tag-shaped,
        // so bare '<' or '>' on its own is left alone.
        String input = "defendant aged < 18 years and his sister is aged < 20";
        assertThat(sanitizer.sanitize(input)).isSameAs(input);
    }

    @Test
    void outputAlwaysSatisfiesSchemaRegex() {
        String[] inputs = {
                "Contrary to s.40.<br /><br />",
                "<p>hello</p>",
                "&lt;br/&gt;hello",
                "before<br/>middle&lt;/p&gt;after",
                "<a href=\"x\">link</a> and &lt;span&gt;more&lt;/span&gt;",
                "defendant aged < 18 years > old",
                "no tags here",
                "",
                "<br />",
        };
        for (String input : inputs) {
            String sanitized = sanitizer.sanitize(input);
            if (sanitized == null) {
                // null is the most-stripped possible output and trivially satisfies the schema
                continue;
            }
            assertThat(FORBIDDEN_RAW.matcher(sanitized).find())
                    .as("sanitized output must not contain raw tag-like substring: <%s>", sanitized)
                    .isFalse();
            assertThat(FORBIDDEN_ENTITY.matcher(sanitized).find())
                    .as("sanitized output must not contain entity-encoded tag-like substring: <%s>", sanitized)
                    .isFalse();
        }
    }
}
