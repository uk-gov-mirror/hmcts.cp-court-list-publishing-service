package uk.gov.hmcts.cp.services.sjp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.services.CourtListPublisher;
import uk.gov.hmcts.cp.services.JsonSchemaValidatorService;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;
import uk.gov.hmcts.cp.services.sanitization.HtmlStrippingSanitizer;
import uk.gov.hmcts.cp.services.sanitization.RequiredStringFieldsRegistry;
import uk.gov.hmcts.cp.services.sanitization.WafPatternSanitizer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SjpCourtListPublishServiceTest {

    @Mock
    private CourtListPublisher courtListPublisher;

    @Mock
    private JsonSchemaValidatorService jsonSchemaValidatorService;

    private SjpCourtListPublishService service;

    /** Minimal valid readyCases entry */
    private static final List<Map<String, Object>> ONE_CASE = List.of(Map.of(
            "caseUrn", "URN1",
            "defendantName", "D",
            "prosecutorName", "P",
            "sjpOffences", List.of(Map.of("title", "t", "wording", "w"))));

    private static final DocumentSanitizer SANITIZER = new DocumentSanitizer(
            new WafPatternSanitizer("..\\.\\,../"),
            new HtmlStrippingSanitizer(),
            new RequiredStringFieldsRegistry());

    @BeforeEach
    void setUp() {
        service = new SjpCourtListPublishService(
                new SjpToCathPayloadTransformer(),
                courtListPublisher,
                SANITIZER,
                jsonSchemaValidatorService,
                true);
    }

    // ── courtId ─────────────────────────────────────────────────────────────

    @Test
    void publishSjpCourtList_usesCourtIdNumericOnDtsMeta_whenPresent() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "325");

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        DtsMeta meta = capturePublishedMeta();
        assertThat(meta.getCourtId()).isEqualTo("325");
    }

    @Test
    void publishSjpCourtList_fallsBackToZeroOnDtsMeta_whenCourtIdNumericBlank() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "   ");

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(capturePublishedMeta().getCourtId()).isEqualTo("0");
    }

    // ── language from isWelsh ────────────────────────────────────────────────

    @Test
    void publishSjpCourtList_setsLanguageToWelsh_whenPayloadIsWelshTrue() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, null, true);

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(capturePublishedMeta().getLanguage()).isEqualTo("WELSH");
    }

    @Test
    void publishSjpCourtList_setsLanguageToEnglish_whenPayloadIsWelshFalse() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, null, false);

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(capturePublishedMeta().getLanguage()).isEqualTo("ENGLISH");
    }

    @Test
    void publishSjpCourtList_defaultsToEnglish_whenPayloadIsWelshNull() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, null, null);

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(capturePublishedMeta().getLanguage()).isEqualTo("ENGLISH");
    }

    @Test
    void publishSjpCourtList_explicitLanguageOverridesIsWelsh() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        // isWelsh=true but explicit language="ENGLISH" should win
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, null, true);

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, "ENGLISH", null, payload);

        assertThat(capturePublishedMeta().getLanguage()).isEqualTo("ENGLISH");
    }

    // ── requestType ──────────────────────────────────────────────────────────

    @Test
    void publishSjpCourtList_passesRequestTypeToMeta_whenProvided() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, "FULL", payload);

        assertThat(capturePublishedMeta().getRequestType()).isEqualTo("FULL");
    }

    @Test
    void publishSjpCourtList_requestTypeIsNull_whenNotProvided() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(capturePublishedMeta().getRequestType()).isNull();
    }

    // ── cath publishing disabled ─────────────────────────────────────────────

    @Test
    void publishSjpCourtList_returnsAccepted_whenCathPublishingDisabled() {
        SjpCourtListPublishService disabledService =
                new SjpCourtListPublishService(
                        new SjpToCathPayloadTransformer(),
                        courtListPublisher,
                        SANITIZER,
                        jsonSchemaValidatorService,
                        false);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        SjpCourtListPublishService.SjpPublishResult result =
                disabledService.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        assertThat(result.getMessage()).contains("disabled");
        verify(courtListPublisher, never()).publish(anyString(), any(DtsMeta.class));
    }

    // ── guard clauses ────────────────────────────────────────────────────────

    @Test
    void publishSjpCourtList_returnsFailed_whenListPayloadNull() {
        SjpCourtListPublishService.SjpPublishResult result =
                service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, null);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("listPayload is required");
    }

    @Test
    void publishSjpCourtList_returnsAccepted_whenNoReadyCases() {
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", List.of());

        SjpCourtListPublishService.SjpPublishResult result =
                service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        assertThat(result.getMessage()).contains("no readyCases");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private DtsMeta capturePublishedMeta() {
        ArgumentCaptor<DtsMeta> captor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(courtListPublisher).publish(anyString(), captor.capture());
        return captor.getValue();
    }
}
