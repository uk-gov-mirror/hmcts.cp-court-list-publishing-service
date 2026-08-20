package uk.gov.hmcts.cp.services.sjp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.SjpListType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SjpStatusListTypeMapperTest {

    @ParameterizedTest
    @CsvSource({
            "SJP_PUBLIC_LIST, ENGLISH, SJP_PUBLIC_FULL_ENGLISH",
            "SJP_PUBLIC_LIST, WELSH, SJP_PUBLIC_FULL_WELSH",
            "SJP_DELTA_PUBLIC_LIST, ENGLISH, SJP_PUBLIC_DELTA_ENGLISH",
            "SJP_DELTA_PUBLIC_LIST, WELSH, SJP_PUBLIC_DELTA_WELSH",
            "SJP_PRESS_LIST, ENGLISH, SJP_PRESS_FULL_ENGLISH",
            "SJP_PRESS_LIST, WELSH, SJP_PRESS_FULL_WELSH",
            "SJP_DELTA_PRESS_LIST, ENGLISH, SJP_PRESS_DELTA_ENGLISH",
            "SJP_DELTA_PRESS_LIST, WELSH, SJP_PRESS_DELTA_WELSH",
    })
    void toCourtListType_returnsExpectedFusedType_forEachListTypeAndLanguageCombination(
            SjpListType sjpListType, String language, CourtListType expected) {
        assertThat(SjpStatusListTypeMapper.toCourtListType(sjpListType, language)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"english", "  ENGLISH  ", "English"})
    void toCourtListType_normalisesLanguage_caseAndWhitespaceInsensitive(String language) {
        assertThat(SjpStatusListTypeMapper.toCourtListType(SjpListType.SJP_PUBLIC_LIST, language))
                .isEqualTo(CourtListType.SJP_PUBLIC_FULL_ENGLISH);
    }

    @Test
    void toCourtListType_throws_whenSjpListTypeIsNull() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SjpStatusListTypeMapper.toCourtListType(null, "ENGLISH"))
                .withMessageContaining("Unknown SJP list type");
    }

    @Test
    void toCourtListType_throws_whenLanguageIsUnrecognised() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SjpStatusListTypeMapper.toCourtListType(SjpListType.SJP_PUBLIC_LIST, "FRENCH"))
                .withMessageContaining("Unknown SJP language: FRENCH");
    }

    @Test
    void toCourtListType_throws_whenLanguageIsNull() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SjpStatusListTypeMapper.toCourtListType(SjpListType.SJP_PUBLIC_LIST, null))
                .withMessageContaining("Unknown SJP language");
    }
}
