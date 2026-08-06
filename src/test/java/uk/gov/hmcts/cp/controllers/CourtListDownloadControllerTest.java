package uk.gov.hmcts.cp.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cleanup.CleanupJobService;
import uk.gov.hmcts.cp.models.CourtCentreData;
import uk.gov.hmcts.cp.services.CourtListPublishStatusService;
import uk.gov.hmcts.cp.services.CourtListTaskTriggerService;
import uk.gov.hmcts.cp.services.ReferenceDataService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListFileResult;
import uk.gov.hmcts.cp.services.sjp.SjpCourtListPublishService;

import java.time.LocalDate;
import java.util.Optional;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CourtListDownloadControllerTest {

    private static final String COURT_CENTRE_ID = "f8254db1-1683-483e-afb3-b87fde5a0a26";
    private static final String START_DATE = "2026-02-27";
    private static final String END_DATE = "2026-02-27";
    private static final String DOWNLOAD_URL = "/api/court-list-publish/download";
    private static final String PRISON_DOWNLOAD_URL = "/api/court-list-publish/prison/download";
    private static final String PRISON_DOWNLOAD_ACCEPT = "application/vnd.courtlistpublishing-service.prison-download.get+json";
    private static final String CJSCPPUID_HEADER = "CJSCPPUID";
    private static final String CJSCPPUID_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";
    private static final String DOWNLOAD_ACCEPT = "application/vnd.courtlistpublishing-service.download.get+json";
    private static final byte[] PDF_BYTES = "PDF content".getBytes();
    private static final byte[] WORD_BYTES = "Word content".getBytes();
    private static final String WORD_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private MockMvc mockMvc;

    @Mock
    private CourtListPublishStatusService service;
    @Mock
    private CourtListTaskTriggerService courtListTaskTriggerService;
    @Mock
    private CourtListDownloadService courtListDownloadService;
    @Mock
    private SjpCourtListPublishService sjpCourtListPublishService;
    @Mock
    private CleanupJobService cleanupJobService;
    @Mock
    private ReferenceDataService referenceDataService;

    @BeforeEach
    void setUp() {
        CourtListPublishController controller = new CourtListPublishController(
                service, courtListTaskTriggerService, courtListDownloadService, cleanupJobService,
                sjpCourtListPublishService, referenceDataService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void downloadCourtListReturnsPdfWhenValidQueryParams() throws Exception {
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.PUBLIC),
                eq(COURT_CENTRE_ID),
                isNull(),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(CJSCPPUID_VALUE),
                eq(false)))
                .thenReturn(result);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.pdf\""))
                .andExpect(content().bytes(PDF_BYTES));

        verify(courtListDownloadService).generateCourtListDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), isNull(), any(LocalDate.class), any(LocalDate.class), eq(CJSCPPUID_VALUE), eq(false));
    }

    @Test
    void downloadCourtListReturns400WhenCjscppuidHeaderMissing() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenCourtCentreIdMissing() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenStartDateMissing() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("endDate", END_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenEndDateMissing() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenCourtListTypeMissing() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturnsPdfWhenStandard() throws Exception {
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.STANDARD),
                eq(COURT_CENTRE_ID),
                isNull(),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(CJSCPPUID_VALUE),
                eq(false)))
                .thenReturn(result);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "STANDARD"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.pdf\""))
                .andExpect(content().bytes(PDF_BYTES));
    }

    @Test
    void downloadCourtListReturnsPdfWhenJudge() throws Exception {
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.JUDGE),
                eq(COURT_CENTRE_ID),
                isNull(),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(CJSCPPUID_VALUE),
                eq(false)))
                .thenReturn(result);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "JUDGE"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.pdf\""))
                .andExpect(content().bytes(PDF_BYTES));
    }

    @Test
    void downloadCourtListRejectsPrisonTypeOnGenericEndpoint() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "PRISON"))
                .andExpect(status().isBadRequest());

        verify(courtListDownloadService, org.mockito.Mockito.never()).generateCourtListDownload(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void downloadPrisonCourtListReturnsPdf() throws Exception {
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.PRISON),
                eq(COURT_CENTRE_ID),
                isNull(),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(CJSCPPUID_VALUE),
                eq(false)))
                .thenReturn(result);

        mockMvc.perform(get(PRISON_DOWNLOAD_URL)
                        .header("Accept", PRISON_DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.pdf\""))
                .andExpect(content().bytes(PDF_BYTES));
    }

    @Test
    void downloadPrisonCourtListReturns400WhenCjscppuidHeaderMissing() throws Exception {
        mockMvc.perform(get(PRISON_DOWNLOAD_URL)
                        .header("Accept", PRISON_DOWNLOAD_ACCEPT)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenEndDateBeforeStartDate() throws Exception {
        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", "2026-02-28")
                        .param("endDate", END_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturnsWordWhenUshersCrown() throws Exception {
        CourtListFileResult wordResult = new CourtListFileResult(WORD_BYTES, WORD_CONTENT_TYPE, "CourtList.docx");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.USHERS_CROWN),
                eq(COURT_CENTRE_ID),
                isNull(),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(CJSCPPUID_VALUE),
                eq(false)))
                .thenReturn(wordResult);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "USHERS_CROWN"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(WORD_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.docx\""))
                .andExpect(content().bytes(WORD_BYTES));

        verify(courtListDownloadService).generateCourtListDownload(
                eq(CourtListType.USHERS_CROWN), eq(COURT_CENTRE_ID), isNull(), any(LocalDate.class), any(LocalDate.class), eq(CJSCPPUID_VALUE), eq(false));
    }

    @Test
    void downloadCourtListReturnsWordWhenUshersMagistrate() throws Exception {
        CourtListFileResult wordResult = new CourtListFileResult(WORD_BYTES, WORD_CONTENT_TYPE, "CourtList.docx");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.USHERS_MAGISTRATE),
                eq(COURT_CENTRE_ID),
                isNull(),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(CJSCPPUID_VALUE),
                eq(false)))
                .thenReturn(wordResult);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "USHERS_MAGISTRATE"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(WORD_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.docx\""))
                .andExpect(content().bytes(WORD_BYTES));
    }

    @Test
    void downloadCourtListReturns502WhenServiceThrows() throws Exception {
        when(courtListDownloadService.generateCourtListDownload(any(CourtListType.class), any(), any(), any(LocalDate.class), any(LocalDate.class), any(), anyBoolean()))
                .thenThrow(new CourtListDownloadException("Failed to fetch court list"));

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "PUBLIC"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void downloadCourtListReturnsPdfWhenCrownCourt() throws Exception {
        CourtCentreData crownCourtData = CourtCentreData.builder()
                .oucodeL1Code("C")
                .isWelsh(false)
                .build();
        when(referenceDataService.getCourtCenterDataByCourtCentreId(anyString(), anyString()))
                .thenReturn(Optional.of(crownCourtData));
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCrownCourtPdf(
                eq(CourtListType.ALPHABETICAL), eq(false), eq(COURT_CENTRE_ID),
                isNull(), any(LocalDate.class), any(LocalDate.class), eq(CJSCPPUID_VALUE), eq(false)))
                .thenReturn(result);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "ALPHABETICAL"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(PDF_BYTES));
    }

    @Test
    void downloadCourtListReturnsPdfWhenWelshCrownCourt() throws Exception {
        CourtCentreData crownCourtData = CourtCentreData.builder()
                .oucodeL1Code("C")
                .isWelsh(true)
                .build();
        when(referenceDataService.getCourtCenterDataByCourtCentreId(anyString(), anyString()))
                .thenReturn(Optional.of(crownCourtData));
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCrownCourtPdf(
                eq(CourtListType.ALPHABETICAL), eq(true), eq(COURT_CENTRE_ID),
                isNull(), any(LocalDate.class), any(LocalDate.class), eq(CJSCPPUID_VALUE), eq(false)))
                .thenReturn(result);

        mockMvc.perform(get(DOWNLOAD_URL)
                        .header("Accept", DOWNLOAD_ACCEPT)
                        .header(CJSCPPUID_HEADER, CJSCPPUID_VALUE)
                        .param("courtCentreId", COURT_CENTRE_ID)
                        .param("startDate", START_DATE)
                        .param("endDate", END_DATE)
                        .param("courtListType", "ALPHABETICAL"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(PDF_BYTES));
    }
}
