package uk.gov.hmcts.cp.services.sanitization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Strips substrings that the Azure Web Application Firewall (WAF) blocks from
 * outbound payloads. The canonical example is path-traversal sequences like
 * {@code ../} and {@code ..\} — the WAF rejects the whole request if these
 * appear anywhere in the JSON body, even inside legitimate free-text fields
 * (e.g. an offence narrative that happens to mention a file path).
 *
 * <p>The list of blocked patterns is sourced from the
 * {@code cath.waf-blocked-patterns} property (env var
 * {@code CATH_WAF_BLOCKED_PATTERNS}) as a comma-separated list of literal
 * substrings. Each entry is {@link Pattern#quote}-escaped before being joined
 * into a single regex, so configured values can contain regex meta-characters
 * (backslashes, dots, brackets) without escaping in the property itself.
 *
 * <p>Each match is replaced with a single space. Whitespace cleanup is left to
 * {@link HtmlStrippingSanitizer}, which runs after this sanitiser in
 * {@link DocumentSanitizer}, so any space gaps left behind here are
 * collapsed back to a single space and trimmed in the same pass that handles
 * HTML stripping.
 *
 * <p>An empty or whitespace-only config disables the sanitiser entirely
 * (every input is returned unchanged), which is useful for tests and for
 * environments that don't sit behind a WAF.
 */
@Component
public class WafPatternSanitizer {

    private final Pattern blockedPattern;

    public WafPatternSanitizer(
            @Value("${cath.waf-blocked-patterns:..\\,../}") String patternsCsv) {
        String regex = patternsCsv == null ? "" : Arrays.stream(patternsCsv.split(","))
                .filter(p -> !p.isEmpty())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        this.blockedPattern = regex.isEmpty() ? null : Pattern.compile(regex);
    }

    public String sanitize(String input) {
        if (input == null || input.isEmpty() || blockedPattern == null) {
            return input;
        }
        String cleaned = blockedPattern.matcher(input).replaceAll(" ");
        return cleaned.equals(input) ? input : cleaned;
    }
}
