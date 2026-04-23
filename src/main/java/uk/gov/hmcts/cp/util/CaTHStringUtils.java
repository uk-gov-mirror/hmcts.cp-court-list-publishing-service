package uk.gov.hmcts.cp.util;

public final class CaTHStringUtils {

    private CaTHStringUtils() {
    }

    /**
     * Strip leading/trailing whitespace (including newlines) so downstream consumers like CaTH
     * don't receive invisible surrounding characters. Returns {@code null} for null input or
     * when the value is empty after stripping. Whitespace inside the value is preserved.
     */
    public static String stripSurroundingWhitespace(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
