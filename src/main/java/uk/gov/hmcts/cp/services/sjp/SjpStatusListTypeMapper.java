package uk.gov.hmcts.cp.services.sjp;

import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.SjpListType;

import java.util.Locale;
import java.util.Map;

/**
 * Maps {@link SjpListType} plus a language onto the fused {@link CourtListType} used as the
 * publish-status row key — SJP is national and all eight daily publishes share a publish date,
 * so the fused type is the only row-key discriminator.
 *
 * <p>Unknown language input is rejected, never defaulted — a past bug silently treated any
 * unrecognised list type as public, letting the bogus value {@code SJP_PUBLISH_LIST} through
 * unnoticed (list type itself is now guaranteed valid by {@link SjpListType}).
 */
public final class SjpStatusListTypeMapper {

    private static final String ENGLISH = "ENGLISH";
    private static final String WELSH = "WELSH";

    private static final Map<SjpListType, Map<String, CourtListType>> MAPPINGS = Map.of(
            SjpListType.SJP_PUBLIC_LIST, Map.of(
                    ENGLISH, CourtListType.SJP_PUBLIC_FULL_ENGLISH,
                    WELSH, CourtListType.SJP_PUBLIC_FULL_WELSH),
            SjpListType.SJP_DELTA_PUBLIC_LIST, Map.of(
                    ENGLISH, CourtListType.SJP_PUBLIC_DELTA_ENGLISH,
                    WELSH, CourtListType.SJP_PUBLIC_DELTA_WELSH),
            SjpListType.SJP_PRESS_LIST, Map.of(
                    ENGLISH, CourtListType.SJP_PRESS_FULL_ENGLISH,
                    WELSH, CourtListType.SJP_PRESS_FULL_WELSH),
            SjpListType.SJP_DELTA_PRESS_LIST, Map.of(
                    ENGLISH, CourtListType.SJP_PRESS_DELTA_ENGLISH,
                    WELSH, CourtListType.SJP_PRESS_DELTA_WELSH));

    private SjpStatusListTypeMapper() {
    }

    /**
     * @param sjpListType the SJP list variant being published
     * @param language    ENGLISH or WELSH (case-insensitive)
     * @return the fused CourtListType used to key the publish-status row
     * @throws IllegalArgumentException if either argument is unrecognised
     */
    public static CourtListType toCourtListType(final SjpListType sjpListType, final String language) {
        if (sjpListType == null) {
            throw new IllegalArgumentException("Unknown SJP list type: null");
        }
        final Map<String, CourtListType> byLanguage = MAPPINGS.get(sjpListType);
        if (byLanguage == null) {
            throw new IllegalArgumentException("Unknown SJP list type: " + sjpListType);
        }
        final CourtListType courtListType = byLanguage.get(normalise(language));
        if (courtListType == null) {
            throw new IllegalArgumentException("Unknown SJP language: " + language);
        }
        return courtListType;
    }

    private static String normalise(final String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
