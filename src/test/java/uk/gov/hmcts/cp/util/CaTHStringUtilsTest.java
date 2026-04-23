package uk.gov.hmcts.cp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaTHStringUtilsTest {

    @Test
    void stripSurroundingWhitespace_shouldReturnNullForNullInput() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace(null)).isNull();
    }

    @Test
    void stripSurroundingWhitespace_shouldReturnNullForEmptyString() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("")).isNull();
    }

    @Test
    void stripSurroundingWhitespace_shouldReturnNullForWhitespaceOnlyString() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("   \n\t ")).isNull();
    }

    @Test
    void stripSurroundingWhitespace_shouldStripTrailingNewlineAndSpace() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("Some offence wording\n "))
                .isEqualTo("Some offence wording");
    }

    @Test
    void stripSurroundingWhitespace_shouldStripLeadingWhitespace() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("\n Some offence wording"))
                .isEqualTo("Some offence wording");
    }

    @Test
    void stripSurroundingWhitespace_shouldStripBothEnds() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("\n Some offence wording\n "))
                .isEqualTo("Some offence wording");
    }

    @Test
    void stripSurroundingWhitespace_shouldPreserveWhitespaceInTheMiddle() {
        String withInternalWhitespace = "First line\n Second line\tthird\n\nfourth";
        assertThat(CaTHStringUtils.stripSurroundingWhitespace(withInternalWhitespace))
                .isEqualTo(withInternalWhitespace);
    }

    @Test
    void stripSurroundingWhitespace_shouldStripOnlySurroundingAndKeepInternalWhitespace() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("\n First line\n Second line\n "))
                .isEqualTo("First line\n Second line");
    }

    @Test
    void stripSurroundingWhitespace_shouldReturnValueUnchangedWhenNoSurroundingWhitespace() {
        assertThat(CaTHStringUtils.stripSurroundingWhitespace("Clean wording"))
                .isEqualTo("Clean wording");
    }
}
