package uk.gov.hmcts.cp.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.services.CourtListPublishStatusService;
import uk.gov.hmcts.cp.services.CourtListTaskTriggerService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListFileResult;
import uk.gov.hmcts.cp.services.sjp.SjpCourtListPublishService;

import java.time.LocalDate;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CourtListDownloadControllerTest {

    private static final String COURT_CENTRE_ID = "f8254db1-1683-483e-afb3-b87fde5a0a26";
    private static final String START_DATE = "2026-02-27";
    private static final String END_DATE = "2026-02-27";
    private static final String DOWNLOAD_URL = "/api/court-list-publish/download";
    private static final String DOWNLOAD_CONTENT_TYPE = "application/vnd.courtlistpublishing-service.download.post+json";
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

    @BeforeEach
    void setUp() {
        CourtListPublishController controller = new CourtListPublishController(
                service, courtListTaskTriggerService, courtListDownloadService, sjpCourtListPublishService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String downloadRequestJson(String courtCentreId, String startDate, String endDate) {
        return """
            {
              "courtCentreId": "%s",
              "startDate": "%s",
              "endDate": "%s",
              "courtListType": "PUBLIC"
            }
            """.formatted(courtCentreId, startDate, endDate);
    }

    @Test
    void downloadCourtListReturnsPdfWhenValidRequestBody() throws Exception {
        CourtListFileResult result = new CourtListFileResult(PDF_BYTES, "application/pdf", "CourtList.pdf");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.PUBLIC),
                eq(COURT_CENTRE_ID),
                any(LocalDate.class),
                any(LocalDate.class)))
                .thenReturn(result);

        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content(downloadRequestJson(COURT_CENTRE_ID, START_DATE, END_DATE)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.pdf\""))
                .andExpect(content().bytes(PDF_BYTES));

        verify(courtListDownloadService).generateCourtListDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void downloadCourtListReturns400WhenCourtCentreIdMissing() throws Exception {
        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content("""
                            {"startDate": "%s", "endDate": "%s", "courtListType": "PUBLIC"}
                            """.formatted(START_DATE, END_DATE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenStartDateMissing() throws Exception {
        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content("""
                            {"courtCentreId": "%s", "endDate": "%s", "courtListType": "PUBLIC"}
                            """.formatted(COURT_CENTRE_ID, END_DATE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenEndDateMissing() throws Exception {
        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content("""
                            {"courtCentreId": "%s", "startDate": "%s", "courtListType": "PUBLIC"}
                            """.formatted(COURT_CENTRE_ID, START_DATE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenCourtListTypeMissing() throws Exception {
        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content("""
                            {"courtCentreId": "%s", "startDate": "%s", "endDate": "%s"}
                            """.formatted(COURT_CENTRE_ID, START_DATE, END_DATE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenCourtListTypeNotPublic() throws Exception {
        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content("""
                            {"courtCentreId": "%s", "startDate": "%s", "endDate": "%s", "courtListType": "STANDARD"}
                            """.formatted(COURT_CENTRE_ID, START_DATE, END_DATE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturns400WhenEndDateBeforeStartDate() throws Exception {
        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content(downloadRequestJson(COURT_CENTRE_ID, "2026-02-28", END_DATE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadCourtListReturnsWordWhenUshersMagistrate() throws Exception {
        CourtListFileResult wordResult = new CourtListFileResult(WORD_BYTES, WORD_CONTENT_TYPE, "CourtList.docx");
        when(courtListDownloadService.generateCourtListDownload(
                eq(CourtListType.USHERS_MAGISTRATE),
                eq(COURT_CENTRE_ID),
                any(LocalDate.class),
                any(LocalDate.class)))
                .thenReturn(wordResult);

        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content("""
                            {"courtCentreId": "%s", "startDate": "%s", "endDate": "%s", "courtListType": "USHERS_MAGISTRATE"}
                            """.formatted(COURT_CENTRE_ID, START_DATE, END_DATE)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(WORD_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CourtList.docx\""))
                .andExpect(content().bytes(WORD_BYTES));

        verify(courtListDownloadService).generateCourtListDownload(
                eq(CourtListType.USHERS_MAGISTRATE), eq(COURT_CENTRE_ID), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void downloadCourtListReturns502WhenServiceThrows() throws Exception {
        when(courtListDownloadService.generateCourtListDownload(any(CourtListType.class), any(), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new CourtListDownloadException("Failed to fetch court list"));

        mockMvc.perform(post(DOWNLOAD_URL)
                        .contentType(DOWNLOAD_CONTENT_TYPE)
                        .content(downloadRequestJson(COURT_CENTRE_ID, START_DATE, END_DATE)))
                .andExpect(status().isBadGateway());
    }
}
