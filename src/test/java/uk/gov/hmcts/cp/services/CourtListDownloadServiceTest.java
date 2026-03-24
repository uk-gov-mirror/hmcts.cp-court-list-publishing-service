package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListFileResult;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CourtListDownloadServiceTest {

    private static final String COURT_CENTRE_ID = "f8254db1-1683-483e-afb3-b87fde5a0a26";
    private static final LocalDate START_DATE = LocalDate.of(2026, 2, 27);
    private static final LocalDate END_DATE = LocalDate.of(2026, 2, 27);
    private static final byte[] PDF_BYTES = new byte[]{1, 2, 3};
    private static final String TEMPLATE_PUBLIC_COURT_LIST = "PublicCourtList";
    private static final String KEY_LIST_TYPE = "listType";

    @Mock
    private DocumentGeneratorClient documentGeneratorClient;

    @Mock
    private CourtListDataService courtListDataService;

    private CourtListDownloadService service;

    @BeforeEach
    void setUp() {
        service = new CourtListDownloadService(courtListDataService, documentGeneratorClient);
    }

    @Test
    void generatePublicCourtListPdfReturnsPdfWhenCourtListDataSucceedsAndDocGenReturnsPdf() throws IOException {
        Map<String, Object> payload = Map.of(
                "templateName", TEMPLATE_PUBLIC_COURT_LIST,
                KEY_LIST_TYPE, "public",
                "courtCentreName", "Test Court");
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(payload);
        when(documentGeneratorClient.generatePdf(any(), eq(TEMPLATE_PUBLIC_COURT_LIST))).thenReturn(PDF_BYTES);

        byte[] result = service.generatePublicCourtListPdf(COURT_CENTRE_ID, START_DATE, END_DATE);

        assertThat(result).isEqualTo(PDF_BYTES);
        verify(courtListDataService).getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE));
        verify(documentGeneratorClient).generatePdf(any(), eq(TEMPLATE_PUBLIC_COURT_LIST));
    }

    @Test
    void generatePublicCourtListPdfUsesDefaultTemplateWhenPayloadHasNoTemplateName() throws IOException {
        Map<String, Object> payload = Map.of(KEY_LIST_TYPE, "public");
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(payload);
        when(documentGeneratorClient.generatePdf(any(), eq(TEMPLATE_PUBLIC_COURT_LIST))).thenReturn(PDF_BYTES);

        byte[] result = service.generatePublicCourtListPdf(COURT_CENTRE_ID, START_DATE, END_DATE);

        assertThat(result).isEqualTo(PDF_BYTES);
        verify(documentGeneratorClient).generatePdf(any(), eq(TEMPLATE_PUBLIC_COURT_LIST));
    }

    @Test
    void generatePublicCourtListPdfThrowsWhenDocumentGeneratorClientThrows() throws IOException {
        Map<String, Object> payload = Map.of("templateName", TEMPLATE_PUBLIC_COURT_LIST);
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(payload);
        when(documentGeneratorClient.generatePdf(any(), any())).thenThrow(new IOException("Document generator failed"));

        assertThatThrownBy(() -> service.generatePublicCourtListPdf(COURT_CENTRE_ID, START_DATE, END_DATE))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Document generator failed");
    }

    @Test
    void generatePublicCourtListPdfThrowsWhenCourtListDataReturnsEmpty() {
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.generatePublicCourtListPdf(COURT_CENTRE_ID, START_DATE, END_DATE))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void generatePublicCourtListPdfThrowsWhenCourtListDataReturnsNull() {
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(null);

        assertThatThrownBy(() -> service.generatePublicCourtListPdf(COURT_CENTRE_ID, START_DATE, END_DATE))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void generateCourtListPdfUsesBenchTemplateWhenCourtListTypeIsBench() throws IOException {
        Map<String, Object> payload = Map.of(KEY_LIST_TYPE, "bench", "courtCentreName", "Test Court");
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.BENCH), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(payload);
        when(documentGeneratorClient.generatePdf(any(), eq("BenchCourtList"))).thenReturn(PDF_BYTES);

        byte[] result = service.generateCourtListPdf(CourtListType.BENCH, COURT_CENTRE_ID, START_DATE, END_DATE);

        assertThat(result).isEqualTo(PDF_BYTES);
        verify(courtListDataService).getCourtListPayloadForDownload(
                eq(CourtListType.BENCH), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE));
        verify(documentGeneratorClient).generatePdf(any(), eq("BenchCourtList"));
    }

    @Test
    void generateCourtListDownloadReturnsPdfResultWhenPublic() throws IOException {
        Map<String, Object> payload = Map.of(KEY_LIST_TYPE, "public", "courtCentreName", "Test Court");
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE))).thenReturn(payload);
        when(documentGeneratorClient.generatePdf(any(), eq(TEMPLATE_PUBLIC_COURT_LIST))).thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.PUBLIC, COURT_CENTRE_ID, START_DATE, END_DATE);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.filename()).isEqualTo("CourtList.pdf");
    }

    @Test
    void generateCourtListDownloadReturnsWordResultWhenUshersMagistrate() {
        byte[] wordBytes = new byte[]{1, 2, 3, 4};
        CourtListFileResult wordResult = new CourtListFileResult(
                wordBytes,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "CourtList.docx");
        when(courtListDataService.getCourtListFileForDownload(
                eq(CourtListType.USHERS_MAGISTRATE), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE)))
                .thenReturn(wordResult);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.USHERS_MAGISTRATE, COURT_CENTRE_ID, START_DATE, END_DATE);

        assertThat(result.content()).isEqualTo(wordBytes);
        assertThat(result.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(result.filename()).isEqualTo("CourtList.docx");
        verify(courtListDataService).getCourtListFileForDownload(
                eq(CourtListType.USHERS_MAGISTRATE), eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE));
    }
}
