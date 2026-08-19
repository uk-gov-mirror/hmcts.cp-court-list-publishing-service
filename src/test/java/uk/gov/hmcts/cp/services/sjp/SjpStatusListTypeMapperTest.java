package uk.gov.hmcts.cp.services.sjp;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.services.sjp.SjpStatusListTypeMapper.toCourtListType;

/**
 * The eight daily SJP publishes must land on eight distinct publish-status rows.
 * SJP is national (no court centre) and all eight share a publish date, so
 * court_list_type is the only discriminator available - it therefore has to fuse
 * audience, request type and language.
 */
class SjpStatusListTypeMapperTest {

    private static final List<String> LIST_TYPES = List.of(
            "SJP_PUBLIC_LIST", "SJP_DELTA_PUBLIC_LIST", "SJP_PRESS_LIST", "SJP_DELTA_PRESS_LIST");
    private static final List<String> LANGUAGES = List.of("ENGLISH", "WELSH");

    @Test
    void mapsPublicFullEnglish() {
        assertThat(toCourtListType("SJP_PUBLIC_LIST", "ENGLISH"))
                .isEqualTo(CourtListType.SJP_PUBLIC_FULL_ENGLISH);
    }

    @Test
    void mapsPublicFullWelsh() {
        assertThat(toCourtListType("SJP_PUBLIC_LIST", "WELSH"))
                .isEqualTo(CourtListType.SJP_PUBLIC_FULL_WELSH);
    }

    @Test
    void mapsPublicDeltaEnglish() {
        assertThat(toCourtListType("SJP_DELTA_PUBLIC_LIST", "ENGLISH"))
                .isEqualTo(CourtListType.SJP_PUBLIC_DELTA_ENGLISH);
    }

    @Test
    void mapsPublicDeltaWelsh() {
        assertThat(toCourtListType("SJP_DELTA_PUBLIC_LIST", "WELSH"))
                .isEqualTo(CourtListType.SJP_PUBLIC_DELTA_WELSH);
    }

    @Test
    void mapsPressFullEnglish() {
        assertThat(toCourtListType("SJP_PRESS_LIST", "ENGLISH"))
                .isEqualTo(CourtListType.SJP_PRESS_FULL_ENGLISH);
    }

    @Test
    void mapsPressFullWelsh() {
        assertThat(toCourtListType("SJP_PRESS_LIST", "WELSH"))
                .isEqualTo(CourtListType.SJP_PRESS_FULL_WELSH);
    }

    @Test
    void mapsPressDeltaEnglish() {
        assertThat(toCourtListType("SJP_DELTA_PRESS_LIST", "ENGLISH"))
                .isEqualTo(CourtListType.SJP_PRESS_DELTA_ENGLISH);
    }

    @Test
    void mapsPressDeltaWelsh() {
        assertThat(toCourtListType("SJP_DELTA_PRESS_LIST", "WELSH"))
                .isEqualTo(CourtListType.SJP_PRESS_DELTA_WELSH);
    }

    @Test
    void allEightCombinationsMapToDistinctCourtListTypes() {
        long distinct = LIST_TYPES.stream()
                .flatMap(lt -> LANGUAGES.stream().map(lang -> toCourtListType(lt, lang)))
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(8);
    }

    @Test
    void rejectsUnknownListTypeInsteadOfSilentlyTreatingItAsPublic() {
        assertThatThrownBy(() -> toCourtListType("SJP_PUBLISH_LIST", "ENGLISH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SJP_PUBLISH_LIST");
    }

    @Test
    void rejectsUnknownLanguageInsteadOfDefaultingToEnglish() {
        assertThatThrownBy(() -> toCourtListType("SJP_PUBLIC_LIST", "FRENCH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FRENCH");
    }

    @Test
    void rejectsNullListType() {
        assertThatThrownBy(() -> toCourtListType(null, "ENGLISH"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyMappedValueIsAnSjpCourtListType() {
        assertThat(LIST_TYPES.stream()
                .flatMap(lt -> LANGUAGES.stream().map(lang -> toCourtListType(lt, lang)))
                .allMatch(t -> t.getValue().startsWith("SJP_")))
                .isTrue();
    }

    @Test
    void mappingIsExhaustiveOverTheSjpListTypeVocabulary() {
        // Guards against a new SjpListType value being added without a status mapping.
        assertThat(Arrays.stream(uk.gov.hmcts.cp.openapi.model.SjpListType.values())
                .allMatch(lt -> {
                    try {
                        return toCourtListType(lt.getValue(), "ENGLISH") != null;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }))
                .isTrue();
    }
}
