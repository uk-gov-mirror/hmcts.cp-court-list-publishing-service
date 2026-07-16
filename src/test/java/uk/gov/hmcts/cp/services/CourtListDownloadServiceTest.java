package uk.gov.hmcts.cp.services;

import jakarta.json.JsonObject;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourtListDownloadServiceTest {

    private static final String COURT_CENTRE_ID = "f8254db1-1683-483e-afb3-b87fde5a0a26";
    private static final LocalDate START_DATE = LocalDate.of(2026, 2, 27);
    private static final LocalDate END_DATE = LocalDate.of(2026, 2, 27);
    private static final String CJSCPPUID = "a085e359-6069-4694-8820-7810e7dfe762";
    private static final byte[] PDF_BYTES = "%PDF-1.6 fake".getBytes();
    private static final byte[] WORD_BYTES = new byte[]{'P', 'K', 3, 4, 1, 2, 3};
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String WORD_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    private CourtListDataService courtListDataService;

    @Mock
    private DocumentGeneratorClient documentGeneratorClient;

    private CourtListDownloadService service;

    @BeforeEach
    void setUp() {
        service = new CourtListDownloadService(courtListDataService, documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadDelegatesStandardToProgressionPdf() {
        when(courtListDataService.fetchCourtListPdfFromProgression(
                eq(CourtListType.STANDARD), eq(COURT_CENTRE_ID), isNull(),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.STANDARD, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo(PDF_CONTENT_TYPE);
        assertThat(result.filename()).isEqualTo("CourtList.pdf");
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadDelegatesBenchToProgressionPdf() {
        when(courtListDataService.fetchCourtListPdfFromProgression(
                eq(CourtListType.BENCH), eq(COURT_CENTRE_ID), isNull(),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.BENCH, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo(PDF_CONTENT_TYPE);
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadDelegatesPublicToProgressionPdf() {
        when(courtListDataService.fetchCourtListPdfFromProgression(
                eq(CourtListType.PUBLIC), eq(COURT_CENTRE_ID), isNull(),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.PUBLIC, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo(PDF_CONTENT_TYPE);
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadDelegatesAlphabeticalToListingPdf() {
        when(courtListDataService.fetchCourtListPdfFromListing(
                eq(CourtListType.ALPHABETICAL), eq(COURT_CENTRE_ID), isNull(),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.ALPHABETICAL, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo(PDF_CONTENT_TYPE);
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadDelegatesJudgeToListingPdf() {
        when(courtListDataService.fetchCourtListPdfFromListing(
                eq(CourtListType.JUDGE), eq(COURT_CENTRE_ID), isNull(),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.JUDGE, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadRendersWordForUshersCrownUsingListingTemplateName() throws IOException {
        String payloadJson = "{\"listType\":\"ushers_crown\",\"templateName\":\"UshersCrownList\"}";
        stubListingPayload(CourtListType.USHERS_CROWN, null, payloadJson);
        when(documentGeneratorClient.generateWord(any(JsonObject.class), eq("UshersCrownList")))
                .thenReturn(WORD_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.USHERS_CROWN, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(WORD_BYTES);
        assertThat(result.contentType()).isEqualTo(WORD_CONTENT_TYPE);
        assertThat(result.filename()).isEqualTo("CourtList.docx");
        verify(documentGeneratorClient).generateWord(any(JsonObject.class), eq("UshersCrownList"));
    }

    @Test
    void generateCourtListDownloadRendersWordForUshersMagistrateUsingListingTemplateName() throws IOException {
        String payloadJson = "{\"listType\":\"ushers_magistrate\",\"templateName\":\"UshersMagistrateList\"}";
        stubListingPayload(CourtListType.USHERS_MAGISTRATE, null, payloadJson);
        when(documentGeneratorClient.generateWord(any(JsonObject.class), eq("UshersMagistrateList")))
                .thenReturn(WORD_BYTES);

        service.generateCourtListDownload(
                CourtListType.USHERS_MAGISTRATE, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generateWord(any(JsonObject.class), eq("UshersMagistrateList"));
    }

    @Test
    void generateCourtListDownloadForwardsCourtRoomIdToProgression() {
        String courtRoomId = "4294a92c-8827-3296-be53-c74b7e9e31d8";
        when(courtListDataService.fetchCourtListPdfFromProgression(
                eq(CourtListType.STANDARD), eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        service.generateCourtListDownload(
                CourtListType.STANDARD, COURT_CENTRE_ID, courtRoomId, START_DATE, END_DATE, CJSCPPUID, false);

        verify(courtListDataService).fetchCourtListPdfFromProgression(
                eq(CourtListType.STANDARD), eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false));
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadForwardsCourtRoomIdToListingForAlphabetical() {
        String courtRoomId = "4294a92c-8827-3296-be53-c74b7e9e31d8";
        when(courtListDataService.fetchCourtListPdfFromListing(
                eq(CourtListType.ALPHABETICAL), eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(PDF_BYTES);

        service.generateCourtListDownload(
                CourtListType.ALPHABETICAL, COURT_CENTRE_ID, courtRoomId, START_DATE, END_DATE, CJSCPPUID, false);

        verify(courtListDataService).fetchCourtListPdfFromListing(
                eq(CourtListType.ALPHABETICAL), eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false));
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadFallsBackToRegisteredTemplateWhenUshersPayloadOmitsTemplateName() throws IOException {
        String payloadWithoutTemplate = "{\"listType\":\"ushers_crown\"}";
        stubListingPayload(CourtListType.USHERS_CROWN, null, payloadWithoutTemplate);
        when(documentGeneratorClient.generateWord(any(JsonObject.class), eq("UshersCrownList")))
                .thenReturn(WORD_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.USHERS_CROWN, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(WORD_BYTES);
        assertThat(result.filename()).isEqualTo("CourtList.docx");
        verify(documentGeneratorClient).generateWord(any(JsonObject.class), eq("UshersCrownList"));
    }

    @Test
    void generateCourtListDownloadRendersPdfForPrisonUsingRegisteredTemplate() throws IOException {
        String payloadJson = "{\"listType\":\"prison\"}";
        stubListingPayload(CourtListType.PRISON, null, payloadJson);
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("PrisonCourtList")))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCourtListDownload(
                CourtListType.PRISON, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo(PDF_CONTENT_TYPE);
        assertThat(result.filename()).isEqualTo("CourtList.pdf");
        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("PrisonCourtList"));
    }

    @Test
    void generateCourtListDownloadRejectsUnsupportedTypeBeforeCallingListing() {
        assertThatThrownBy(() -> service.generateCourtListDownload(
                CourtListType.ONLINE_PUBLIC, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Unsupported court list type for download");
        verifyNoInteractions(courtListDataService);
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCourtListDownloadThrowsWhenDocumentGeneratorFails() throws IOException {
        String payloadJson = "{\"listType\":\"ushers_crown\",\"templateName\":\"UshersCrownList\"}";
        stubListingPayload(CourtListType.USHERS_CROWN, null, payloadJson);
        when(documentGeneratorClient.generateWord(any(JsonObject.class), eq("UshersCrownList")))
                .thenThrow(new IOException("docgen down"));

        assertThatThrownBy(() -> service.generateCourtListDownload(
                CourtListType.USHERS_CROWN, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Failed to render court list document");
    }

    @Test
    void generateCourtListDownloadThrowsWhenUshersPayloadJsonInvalid() {
        stubListingPayload(CourtListType.USHERS_CROWN, null, "not valid json");

        assertThatThrownBy(() -> service.generateCourtListDownload(
                CourtListType.USHERS_CROWN, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Failed to parse court list payload JSON");
    }

    @Test
    void generateCrownCourtPdfUsesCrownDailyListTemplateForDraft() throws IOException {
        stubCrownPayload(CourtListType.DRAFT, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownDailyList")))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCrownCourtPdf(
                CourtListType.DRAFT, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        assertThat(result.contentType()).isEqualTo(PDF_CONTENT_TYPE);
        assertThat(result.filename()).isEqualTo("CourtList.pdf");
        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownDailyList"));
    }

    @Test
    void generateCrownCourtPdfUsesCrownDailyListTemplateForFinal() throws IOException {
        stubCrownPayload(CourtListType.FINAL, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownDailyList")))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCrownCourtPdf(
                CourtListType.FINAL, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownDailyList"));
    }

    @Test
    void generateCrownCourtPdfUsesWelshTemplateWhenIsWelshTrue() throws IOException {
        stubCrownPayload(CourtListType.DRAFT, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownDailyListWelsh")))
                .thenReturn(PDF_BYTES);

        CourtListFileResult result = service.generateCrownCourtPdf(
                CourtListType.DRAFT, true, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        assertThat(result.content()).isEqualTo(PDF_BYTES);
        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownDailyListWelsh"));
    }

    @Test
    void generateCrownCourtPdfUsesAlphabeticalTemplate() throws IOException {
        stubCrownPayload(CourtListType.ALPHABETICAL, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CourtList")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.ALPHABETICAL, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CourtList"));
    }

    @Test
    void generateCrownCourtPdfUsesCrownFirmListTemplateForFirmNonWelsh() throws IOException {
        stubCrownPayload(CourtListType.FIRM, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownFirmList")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.FIRM, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownFirmList"));
    }

    @Test
    void generateCrownCourtPdfUsesCrownFirmListWelshTemplateForFirmWelsh() throws IOException {
        stubCrownPayload(CourtListType.FIRM, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownFirmListWelsh")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.FIRM, true, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownFirmListWelsh"));
    }

    @Test
    void generateCrownCourtPdfUsesOnlinePublicTemplate() throws IOException {
        stubCrownPayload(CourtListType.ONLINE_PUBLIC, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownOnlinePublicCourtList")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.ONLINE_PUBLIC, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownOnlinePublicCourtList"));
    }

    @Test
    void generateCrownCourtPdfUsesOnlinePublicWelshTemplate() throws IOException {
        stubCrownPayload(CourtListType.ONLINE_PUBLIC, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownOnlinePublicCourtListWelsh")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.ONLINE_PUBLIC, true, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CrownOnlinePublicCourtListWelsh"));
    }

    @Test
    void generateCrownCourtPdfUsesAlphabeticalWelshTemplate() throws IOException {
        stubCrownPayload(CourtListType.ALPHABETICAL, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CourtListEnglishWelsh")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.ALPHABETICAL, true, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false);

        verify(documentGeneratorClient).generatePdf(any(JsonObject.class), eq("CourtListEnglishWelsh"));
    }

    @Test
    void generateCrownCourtPdfForwardsCourtRoomId() throws IOException {
        String courtRoomId = "4294a92c-8827-3296-be53-c74b7e9e31d8";
        when(courtListDataService.getCrownCourtDailyListPayload(
                eq(CourtListType.FINAL), eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn("{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownDailyList")))
                .thenReturn(PDF_BYTES);

        service.generateCrownCourtPdf(
                CourtListType.FINAL, false, COURT_CENTRE_ID, courtRoomId, START_DATE, END_DATE, CJSCPPUID, false);

        verify(courtListDataService).getCrownCourtDailyListPayload(
                eq(CourtListType.FINAL), eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false));
    }

    @Test
    void generateCrownCourtPdfRejectsUnsupportedType() {
        assertThatThrownBy(() -> service.generateCrownCourtPdf(
                CourtListType.PRISON, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Unsupported court list type for crown court download");
        verifyNoInteractions(courtListDataService);
        verifyNoInteractions(documentGeneratorClient);
    }

    @Test
    void generateCrownCourtPdfThrowsWhenPayloadJsonInvalid() {
        stubCrownPayload(CourtListType.FINAL, null, "not valid json");

        assertThatThrownBy(() -> service.generateCrownCourtPdf(
                CourtListType.FINAL, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Failed to parse crown court payload JSON");
    }

    @Test
    void generateCrownCourtPdfThrowsWhenDocumentGeneratorFails() throws IOException {
        stubCrownPayload(CourtListType.FINAL, null, "{}");
        when(documentGeneratorClient.generatePdf(any(JsonObject.class), eq("CrownDailyList")))
                .thenThrow(new IOException("docgen down"));

        assertThatThrownBy(() -> service.generateCrownCourtPdf(
                CourtListType.FINAL, false, COURT_CENTRE_ID, null, START_DATE, END_DATE, CJSCPPUID, false))
                .isInstanceOf(CourtListDownloadException.class)
                .hasMessageContaining("Failed to render crown court PDF");
    }

    private void stubListingPayload(CourtListType type, String courtRoomId, String payloadJson) {
        when(courtListDataService.getCourtListPayloadForDownload(
                eq(type), eq(COURT_CENTRE_ID),
                courtRoomId == null ? isNull() : eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(payloadJson);
    }

    private void stubCrownPayload(CourtListType type, String courtRoomId, String payloadJson) {
        when(courtListDataService.getCrownCourtDailyListPayload(
                eq(type), eq(COURT_CENTRE_ID),
                courtRoomId == null ? isNull() : eq(courtRoomId),
                eq(START_DATE), eq(END_DATE), eq(CJSCPPUID), eq(false)))
                .thenReturn(payloadJson);
    }
}
