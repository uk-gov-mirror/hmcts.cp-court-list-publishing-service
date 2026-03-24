package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourtListDataServiceTest {

    @Mock
    private ProgressionQueryService progressionQueryService;

    @InjectMocks
    private CourtListDataService courtListDataService;

    @Test
    void getCourtListDataReturnsProgressionPayloadAsIs() {
        String progressionJson = "{\"listType\":\"standard\",\"courtCentreName\":\"Lavender Hill\",\"ouCode\":\"B01LY00\",\"courtId\":\"f8254db1-1683-483e-afb3-b87fde5a0a26\"}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD),
                eq("f8254db1-1683-483e-afb3-b87fde5a0a26"),
                isNull(),
                eq("2024-01-15"),
                eq("2024-01-15"),
                eq(false),
                eq("request-user-id"),
                eq(false)))
                .thenReturn(progressionJson);

        String result = courtListDataService.getCourtListData(
                CourtListType.STANDARD,
                "f8254db1-1683-483e-afb3-b87fde5a0a26",
                null,
                "2024-01-15",
                "2024-01-15",
                false,
                "request-user-id",
                false);

        assertThat(result).isEqualTo(progressionJson);
        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.STANDARD),
                eq("f8254db1-1683-483e-afb3-b87fde5a0a26"),
                isNull(),
                eq("2024-01-15"),
                eq("2024-01-15"),
                eq(false),
                eq("request-user-id"),
                eq(false));
    }

    @Test
    void getCourtListData_returnsEmptyObjectWhenProgressionReturnsNull() {
        when(progressionQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(null);

        String result = courtListDataService.getCourtListData(
                CourtListType.PUBLIC,
                "f8254db1-1683-483e-afb3-b87fde5a0a26",
                null,
                "2024-01-15",
                "2024-01-15",
                false,
                "user",
                false);

        assertThat(result).isEqualTo("{}");
    }

    @Test
    void getCourtListPayloadReturnsDeserializedPayloadWhenProgressionReturnsValidJson() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD),
                eq("courtCentre1"),
                isNull(),
                eq("2026-01-05"),
                eq("2026-01-12"),
                eq(true),
                eq("user-id"),
                eq(false)))
                .thenReturn("{\"listType\":\"standard\",\"courtCentreName\":\"Test Court\",\"ouCode\":\"B01LY\",\"courtId\":\"f8254db1-1683-483e-afb3-b87fde5a0a26\"}");

        CourtListPayload result = courtListDataService.getCourtListPayload(
                CourtListType.STANDARD, "courtCentre1", "2026-01-05", "2026-01-12", "user-id", false);

        assertThat(result).isNotNull();
        assertThat(result.getListType()).isEqualTo("standard");
        assertThat(result.getCourtCentreName()).isEqualTo("Test Court");
        assertThat(result.getOuCode()).isEqualTo("B01LY");
        assertThat(result.getCourtId()).isEqualTo("f8254db1-1683-483e-afb3-b87fde5a0a26");
    }

    @Test
    void getCourtListPayloadUsesRestrictedFalseWhenCjscppuidIsNull() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.PUBLIC),
                eq("courtCentre1"),
                isNull(),
                eq("2026-01-05"),
                eq("2026-01-12"),
                eq(false),
                isNull(),
                eq(false)))
                .thenReturn("{\"listType\":\"public\",\"courtCentreName\":\"A Court\"}");

        CourtListPayload result = courtListDataService.getCourtListPayload(
                CourtListType.PUBLIC, "courtCentre1", "2026-01-05", "2026-01-12", null, false);

        assertThat(result).isNotNull();
        assertThat(result.getCourtCentreName()).isEqualTo("A Court");
    }

    @Test
    void getCourtListPayload_throws_whenProgressionReturnsInvalidJson() {
        when(progressionQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn("not valid json {{{");

        assertThatThrownBy(() -> courtListDataService.getCourtListPayload(
                CourtListType.STANDARD, "courtCentre1", "2026-01-05", "2026-01-12", null, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse court list payload");
    }

    @Test
    void getPublicCourtListPayloadThrowsWhenNotConfigured() {
        assertThatThrownBy(() -> courtListDataService.getPublicCourtListPayload(
                "f8254db1-1683-483e-afb3-b87fde5a0a26", LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27)))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Court list data is not configured");
    }

    @Test
    void getCourtListPayloadFromCourtListApiThrowsWhenNotConfigured() {
        assertThatThrownBy(() -> courtListDataService.getCourtListPayloadFromCourtListApi(
                "BENCH", "f8254db1-1683-483e-afb3-b87fde5a0a26", LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27)))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Court list data is not configured");
    }

    @Test
    void getCourtListPayloadForDownloadThrowsWhenUnsupportedType() {
        assertThatThrownBy(() -> courtListDataService.getCourtListPayloadForDownload(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26",
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27)))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Unsupported court list type for download");
    }

    @Test
    void getCourtListFileForDownloadThrowsWhenNotConfigured() {
        assertThatThrownBy(() -> courtListDataService.getCourtListFileForDownload(
                CourtListType.USHERS_MAGISTRATE, "f8254db1-1683-483e-afb3-b87fde5a0a26",
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27)))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Court list data is not configured");
    }

}
