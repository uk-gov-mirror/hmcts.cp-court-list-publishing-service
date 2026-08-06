package uk.gov.hmcts.cp.services.sanitization;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Cleans free-text strings before they are sent to CaTH so they satisfy the
 * downstream court-list JSON schema. Two concerns are handled in a single
 * pass:
 *
 * <ol>
 *   <li>HTML/XML-like constructs are stripped — the {@link #TAG_LIKE} regex
 *       matches both raw tags {@code <...>} and HTML-entity-encoded tags
 *       {@code &lt;...&gt;}, covering every anti-HTML pattern declared in
 *       the schemas (see the table on {@link #TAG_LIKE}).</li>
 *   <li>Surrounding whitespace is trimmed and internal whitespace runs are
 *       collapsed to a single space so {@code ^...$}-anchored schema patterns
 *       (dates, timestamps, etc.) survive stray whitespace from upstream.
 *       Only ASCII whitespace is handled — the schemas' own patterns are
 *       ASCII-only, so non-ASCII whitespace is left alone.</li>
 * </ol>
 *
 * <p>If nothing meaningful remains after cleaning, {@code null} is returned.
 * The walker uses this signal to drop the field from the outgoing JSON (via
 * {@code JsonInclude.NON_NULL}), but only for fields that are NOT required by
 * either CaTH schema. Required-field presence is enforced separately by the
 * {@link DocumentSanitizer} after the walk, so this sanitiser is
 * free to signal "nothing to send" without having to know the schema.
 */
@Component
public class HtmlStrippingSanitizer {

    /**
     * Union of every anti-HTML pattern declared in
     * {@code src/main/resources/schema/standard-court-list-schema.json} and
     * {@code src/main/resources/schema/online-public-court-list-schema.json}.
     * Those schemas declare anti-HTML rules as negative-lookahead validation
     * patterns; this sanitizer applies the same rules as a replacement
     * regex, so after {@code TAG_LIKE.matcher(input).replaceAll(...)} no
     * substring forbidden by any of the schema patterns can remain.
     *
     * <p>The two alternations cover all four schema variants:
     * <ul>
     *   <li>{@code <[^>]+>} — any raw tag-like substring (opening, closing,
     *       or self-closing). This subsumes the schema patterns that forbid
     *       any {@code <X>} where {@code X} is non-empty (single-line and
     *       multi-line variants both reduce to the same alternation here
     *       because the matcher operates on the whole input regardless of
     *       newlines).</li>
     *   <li>{@code &lt;[^&]*&gt;} — any HTML-entity-encoded tag-like
     *       substring. This covers the schema patterns that additionally
     *       forbid {@code &lt;/foo&gt;} and {@code &lt;foo/&gt;} entity
     *       forms.</li>
     * </ul>
     *
     * <p>If a new anti-HTML pattern is added to either schema, extend this
     * regex (and the unit tests in {@code HtmlStrippingSanitizerTest}) to
     * cover the new construct.
     */
    private static final Pattern TAG_LIKE = Pattern.compile("<[^>]+>|&lt;[^&]*&gt;");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String sanitize(final String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        final String stripped = TAG_LIKE.matcher(input).replaceAll(" ");
        final String collapsed = WHITESPACE.matcher(stripped).replaceAll(" ").strip();
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.equals(input) ? input : collapsed;
    }
}
