package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourtListDataServiceTest {

    private static final String LISTING_BASE_URL = "https://internal.example.com";
    private static final String LISTING_PATH = "/listing-service/query/api/rest/listing/courtlistpayload";
    private static final String DAILY_LIST_PATH = "/listing-service/query/api/rest/listing/dailylistpayload";

    @Mock
    private RestTemplate publicCourtListRestTemplate;

    @Mock
    private ProgressionQueryService progressionQueryService;

    private CourtListDataService courtListDataService;

    @BeforeEach
    void setUp() {
        courtListDataService = new CourtListDataService(progressionQueryService, publicCourtListRestTemplate, LISTING_BASE_URL);
    }

    @Test
    void getCourtListDataRoutesStandardThroughProgressionForRefdataEnrichment() {
        String enrichedJson = "{\"listType\":\"standard\",\"courtCentreName\":\"Lavender Hill\",\"ouCode\":\"B01LY00\",\"courtId\":\"f8254db1-1683-483e-afb3-b87fde5a0a26\",\"courtIdNumeric\":\"42\",\"isWelsh\":false}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD), anyString(), any(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn(enrichedJson);

        String result = courtListDataService.getCourtListData(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                "2024-01-15", "2024-01-15", false, "request-user-id", false);

        assertThat(result).isEqualTo(enrichedJson);
        verify(progressionQueryService).getCourtListPayload(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                "2024-01-15", "2024-01-15", false, "request-user-id", false);
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListDataRoutesPublicThroughProgression() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.PUBLIC), anyString(), any(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn("{\"listType\":\"public\"}");

        courtListDataService.getCourtListData(
                CourtListType.PUBLIC, "courtCentre", null, "2026-01-05", "2026-01-12", false, "user", false);

        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.PUBLIC), eq("courtCentre"), any(), eq("2026-01-05"), eq("2026-01-12"), eq(false), eq("user"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListDataRoutesBenchThroughProgression() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.BENCH), anyString(), any(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn("{\"listType\":\"bench\"}");

        courtListDataService.getCourtListData(
                CourtListType.BENCH, "courtCentre", null, "2026-01-05", "2026-01-12", false, "user", false);

        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.BENCH), eq("courtCentre"), any(), eq("2026-01-05"), eq("2026-01-12"), eq(false), eq("user"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListDataCallsListingDirectlyForNonEnrichedTypes() {
        String listingJson = "{\"listType\":\"alphabetical\",\"templateName\":\"CourtList\"}";
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(listingJson, HttpStatus.OK));

        String result = courtListDataService.getCourtListData(
                CourtListType.ALPHABETICAL, "courtCentre", null, "2026-01-05", "2026-01-12", false, "user", false);

        assertThat(result).isEqualTo(listingJson);
        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains(LISTING_PATH) && url.contains("listId=ALPHABETICAL")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(progressionQueryService, never()).getCourtListPayload(
                any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void getCourtListDataRoutesUshersCrownThroughProgression() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.USHERS_CROWN), anyString(), any(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn("{\"listType\":\"ushers_crown\"}");

        courtListDataService.getCourtListData(
                CourtListType.USHERS_CROWN, "courtCentre", null, "2026-01-05", "2026-01-12", false, "user", false);

        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.USHERS_CROWN), eq("courtCentre"), any(), eq("2026-01-05"), eq("2026-01-12"), eq(false), eq("user"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListDataRoutesPrisonThroughProgression() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.PRISON), anyString(), any(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn("{\"listType\":\"prison\"}");

        courtListDataService.getCourtListData(
                CourtListType.PRISON, "courtCentre", null, "2026-01-05", "2026-01-12", false, "user", false);

        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.PRISON), eq("courtCentre"), any(), eq("2026-01-05"), eq("2026-01-12"), eq(false), eq("user"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListDataRoutesUshersMagistrateThroughProgression() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.USHERS_MAGISTRATE), anyString(), any(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn("{\"listType\":\"ushers_magistrate\"}");

        courtListDataService.getCourtListData(
                CourtListType.USHERS_MAGISTRATE, "courtCentre", null, "2026-01-05", "2026-01-12", false, "user", false);

        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.USHERS_MAGISTRATE), eq("courtCentre"), any(), eq("2026-01-05"), eq("2026-01-12"), eq(false), eq("user"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListData_throwsWhenListingReturnsEmptyBody() {
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>((String) null, HttpStatus.OK));

        assertThatThrownBy(() -> courtListDataService.getCourtListData(
                CourtListType.ALPHABETICAL, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                "2024-01-15", "2024-01-15", false, "user", false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void getCourtListPayloadCallsProgressionWithRestrictedTrueWhenCjscppuidPresent() {
        String json = "{\"listType\":\"standard\",\"courtCentreName\":\"Test Court\",\"ouCode\":\"B01LY\",\"courtId\":\"f8254db1-1683-483e-afb3-b87fde5a0a26\"}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD), anyString(), any(), anyString(), anyString(), eq(true), anyString(), anyBoolean()))
                .thenReturn(json);

        CourtListPayload result = courtListDataService.getCourtListPayload(
                CourtListType.STANDARD, "courtCentre1", "2026-01-05", "2026-01-12", "user-id", false);

        assertThat(result).isNotNull();
        assertThat(result.getListType()).isEqualTo("standard");
        assertThat(result.getCourtCentreName()).isEqualTo("Test Court");
        assertThat(result.getOuCode()).isEqualTo("B01LY");
        assertThat(result.getCourtId()).isEqualTo("f8254db1-1683-483e-afb3-b87fde5a0a26");
        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.STANDARD), eq("courtCentre1"), any(), eq("2026-01-05"), eq("2026-01-12"), eq(true), eq("user-id"), eq(false));
    }

    @Test
    void getCourtListPayload_throws_whenProgressionReturnsInvalidJson() {
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD), anyString(), any(), anyString(), anyString(), anyBoolean(), any(), anyBoolean()))
                .thenReturn("not valid json {{{");

        assertThatThrownBy(() -> courtListDataService.getCourtListPayload(
                CourtListType.STANDARD, "courtCentre1", "2026-01-05", "2026-01-12", null, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse court list payload");
    }

    @Test
    void getCourtListPayloadForDownloadRoutesStandardThroughProgressionWithRestrictedTrue() {
        String json = "{\"listType\":\"standard\",\"templateName\":\"BenchAndStandardCourtList\",\"ouCode\":\"B01LY\"}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD), anyString(), any(), anyString(), anyString(), eq(true), anyString(), eq(false)))
                .thenReturn(json);

        String result = courtListDataService.getCourtListPayloadForDownload(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", true);

        assertThat(result).isEqualTo(json);
        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.STANDARD), eq("f8254db1-1683-483e-afb3-b87fde5a0a26"), any(),
                eq("2026-02-27"), eq("2026-02-27"), eq(true), eq("user-id"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListPayloadForDownloadRoutesStandardThroughProgressionWithRestrictedFalse() {
        String json = "{\"listType\":\"standard\"}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.STANDARD), anyString(), any(), anyString(), anyString(), eq(false), anyString(), eq(false)))
                .thenReturn(json);

        String result = courtListDataService.getCourtListPayloadForDownload(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        assertThat(result).isEqualTo(json);
        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.STANDARD), eq("f8254db1-1683-483e-afb3-b87fde5a0a26"), any(),
                eq("2026-02-27"), eq("2026-02-27"), eq(false), eq("user-id"), eq(false));
    }

    @Test
    void getCourtListPayloadForDownloadRoutesUshersCrownThroughProgression() {
        String json = "{\"listType\":\"ushers_crown\",\"templateName\":\"UshersCrownList\"}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.USHERS_CROWN), anyString(), any(), anyString(), anyString(), eq(false), anyString(), eq(false)))
                .thenReturn(json);

        String result = courtListDataService.getCourtListPayloadForDownload(
                CourtListType.USHERS_CROWN, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        assertThat(result).isEqualTo(json);
        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.USHERS_CROWN), eq("f8254db1-1683-483e-afb3-b87fde5a0a26"), any(),
                eq("2026-02-27"), eq("2026-02-27"), eq(false), eq("user-id"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListPayloadForDownloadRoutesUshersMagistrateThroughProgression() {
        String json = "{\"listType\":\"ushers_magistrate\",\"templateName\":\"UshersMagistrateList\"}";
        when(progressionQueryService.getCourtListPayload(
                eq(CourtListType.USHERS_MAGISTRATE), anyString(), any(), anyString(), anyString(), eq(false), anyString(), eq(false)))
                .thenReturn(json);

        String result = courtListDataService.getCourtListPayloadForDownload(
                CourtListType.USHERS_MAGISTRATE, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        assertThat(result).isEqualTo(json);
        verify(progressionQueryService).getCourtListPayload(
                eq(CourtListType.USHERS_MAGISTRATE), eq("f8254db1-1683-483e-afb3-b87fde5a0a26"), any(),
                eq("2026-02-27"), eq("2026-02-27"), eq(false), eq("user-id"), eq(false));
        verifyNoInteractions(publicCourtListRestTemplate);
    }

    @Test
    void getCourtListPayloadForDownload_throwsWhenBaseUrlNotConfiguredForListingPath() {
        CourtListDataService serviceWithNoUrl = new CourtListDataService(
                progressionQueryService, publicCourtListRestTemplate, "");

        assertThatThrownBy(() -> serviceWithNoUrl.getCourtListPayloadForDownload(
                CourtListType.ALPHABETICAL, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Court list data is not configured");
    }

    @Test
    void fetchCourtListPdfFromProgressionCallsCourtlistBinaryEndpointAndReturnsBytes() {
        byte[] pdfBytes = "%PDF-1.6 progression".getBytes();
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(pdfBytes, HttpStatus.OK));

        byte[] result = courtListDataService.fetchCourtListPdfFromProgression(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", "room-1",
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 18), "user-id", true);

        assertThat(result).isEqualTo(pdfBytes);
        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains("/progression-service/query/api/rest/progression/courtlist")
                        && !url.contains("/courtlistdata")
                        && url.contains("listId=STANDARD")
                        && url.contains("courtCentreId=f8254db1-1683-483e-afb3-b87fde5a0a26")
                        && url.contains("courtRoomId=room-1")
                        && url.contains("startDate=2026-05-18")
                        && url.contains("endDate=2026-05-18")
                        && url.contains("restricted=true")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class));
    }

    @Test
    void fetchCourtListPdfFromProgressionForwardsRestrictedFalseAsQueryParam() {
        byte[] pdfBytes = "%PDF-1.6 progression-nonrestricted".getBytes();
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(pdfBytes, HttpStatus.OK));

        courtListDataService.fetchCourtListPdfFromProgression(
                CourtListType.BENCH, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 18), "user-id", false);

        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains("listId=BENCH") && url.contains("restricted=false")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class));
    }

    @Test
    void fetchCourtListPdfFromProgression_throwsWhenCjscppuidMissing() {
        assertThatThrownBy(() -> courtListDataService.fetchCourtListPdfFromProgression(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 18), null, true))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("CJSCPPUID");
    }

    @Test
    void fetchCourtListPdfFromProgression_throwsWhenProgressionReturnsEmptyBody() {
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], HttpStatus.OK));

        assertThatThrownBy(() -> courtListDataService.fetchCourtListPdfFromProgression(
                CourtListType.STANDARD, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 18), "user-id", true))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void fetchCourtListPdfFromListingReturnsPdfBytesAndForwardsRestrictedFlag() {
        byte[] pdfBytes = "%PDF-1.6 fake".getBytes();
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(pdfBytes, HttpStatus.OK));

        byte[] result = courtListDataService.fetchCourtListPdfFromListing(
                CourtListType.ALPHABETICAL, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        assertThat(result).isEqualTo(pdfBytes);
        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains("/listing-service/query/api/rest/listing/courtlist")
                        && !url.contains("/courtlistpayload")
                        && url.contains("listId=ALPHABETICAL")
                        && url.contains("courtCentreId=f8254db1-1683-483e-afb3-b87fde5a0a26")
                        && url.contains("startDate=2026-02-27")
                        && url.contains("endDate=2026-02-27")
                        && url.contains("restricted=false")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class));
    }

    @Test
    void fetchCourtListPdfFromListing_throwsWhenListingReturnsEmptyBody() {
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], HttpStatus.OK));

        assertThatThrownBy(() -> courtListDataService.fetchCourtListPdfFromListing(
                CourtListType.JUDGE, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void fetchCourtListPdfFromListing_throwsWhenBaseUrlNotConfigured() {
        CourtListDataService serviceWithNoUrl = new CourtListDataService(
                progressionQueryService, publicCourtListRestTemplate, "");

        assertThatThrownBy(() -> serviceWithNoUrl.fetchCourtListPdfFromListing(
                CourtListType.ALPHABETICAL, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Court list data is not configured");
    }

    @Test
    void getCrownCourtDailyListPayloadCallsListingWithDailyListAcceptHeader() {
        String payload = "{\"listType\":\"draft\"}";
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(payload, HttpStatus.OK));

        String result = courtListDataService.getCrownCourtDailyListPayload(
                CourtListType.DRAFT, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        assertThat(result).isEqualTo(payload);
        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains(DAILY_LIST_PATH) && url.contains("publishCourtListType=DRAFT")
                        && url.contains("courtCentreId=f8254db1-1683-483e-afb3-b87fde5a0a26")
                        && url.contains("startDate=2026-02-27") && url.contains("endDate=2026-02-27")
                        && !url.contains("restricted=") && !url.contains("includeApplications=")),
                eq(HttpMethod.GET),
                argThat((HttpEntity<?> entity) -> entity.getHeaders().getAccept().stream()
                        .anyMatch(mt -> "application/vnd.listing.search.daily.list.payload+json".equals(mt.toString()))),
                eq(String.class));
    }

    @Test
    void getCrownCourtDailyListPayloadForFirmUsesWeekCommencingParams() {
        String payload = "{\"listType\":\"firm\"}";
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(payload, HttpStatus.OK));

        String result = courtListDataService.getCrownCourtDailyListPayload(
                CourtListType.FIRM, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 6), "user-id", false);

        assertThat(result).isEqualTo(payload);
        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains(DAILY_LIST_PATH) && url.contains("publishCourtListType=FIRM")
                        && url.contains("courtCentreId=f8254db1-1683-483e-afb3-b87fde5a0a26")
                        && url.contains("weekCommencingStartDate=2026-09-01") && url.contains("weekCommencingEndDate=2026-09-06")
                        && !url.contains("&startDate=") && !url.contains("&endDate=")),
                eq(HttpMethod.GET),
                argThat((HttpEntity<?> entity) -> entity.getHeaders().getAccept().stream()
                        .anyMatch(mt -> "application/vnd.listing.search.daily.list.payload+json".equals(mt.toString()))),
                eq(String.class));
    }

    @Test
    void getCrownCourtDailyListPayloadDoesNotCallProgressionService() {
        String payload = "{\"listType\":\"final\"}";
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(payload, HttpStatus.OK));

        courtListDataService.getCrownCourtDailyListPayload(
                CourtListType.FINAL, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        verifyNoInteractions(progressionQueryService);
    }

    @Test
    void getCrownCourtDailyListPayloadSendsOnlyRequiredParametersForDailyTypes() {
        String payload = "{\"listType\":\"draft\"}";
        String courtRoomId = "4294a92c-8827-3296-be53-c74b7e9e31d8";
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(payload, HttpStatus.OK));

        courtListDataService.getCrownCourtDailyListPayload(
                CourtListType.DRAFT, "f8254db1-1683-483e-afb3-b87fde5a0a26", courtRoomId,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains("publishCourtListType=DRAFT")
                        && !url.contains("courtRoomId=")
                        && !url.contains("restricted=")
                        && !url.contains("includeApplications=")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void getCrownCourtDailyListPayloadForAlphabeticalUsesCourtListPayloadEndpoint() {
        String payload = "{\"listType\":\"alphabetical\"}";
        String courtRoomId = "4294a92c-8827-3296-be53-c74b7e9e31d8";
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(payload, HttpStatus.OK));

        String result = courtListDataService.getCrownCourtDailyListPayload(
                CourtListType.ALPHABETICAL, "f8254db1-1683-483e-afb3-b87fde5a0a26", courtRoomId,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false);

        assertThat(result).isEqualTo(payload);
        verify(publicCourtListRestTemplate).exchange(
                argThat((String url) -> url.contains(LISTING_PATH)
                        && url.contains("listId=ALPHABETICAL")
                        && !url.contains("publishCourtListType=")),
                eq(HttpMethod.GET),
                argThat((HttpEntity<?> entity) -> entity.getHeaders().getAccept().stream()
                        .anyMatch(mt -> "application/vnd.listing.search.court.list.payload+json".equals(mt.toString()))),
                eq(String.class));
    }

    @Test
    void getCrownCourtDailyListPayloadThrowsWhenBaseUrlNotConfigured() {
        CourtListDataService serviceWithNoUrl = new CourtListDataService(
                progressionQueryService, publicCourtListRestTemplate, "");

        assertThatThrownBy(() -> serviceWithNoUrl.getCrownCourtDailyListPayload(
                CourtListType.DRAFT, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Court list data is not configured");
    }

    @Test
    void getCrownCourtDailyListPayloadThrowsWhenResponseIsEmpty() {
        when(publicCourtListRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>((String) null, HttpStatus.OK));

        assertThatThrownBy(() -> courtListDataService.getCrownCourtDailyListPayload(
                CourtListType.DRAFT, "f8254db1-1683-483e-afb3-b87fde5a0a26", null,
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "user-id", false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("empty response");
    }
}
