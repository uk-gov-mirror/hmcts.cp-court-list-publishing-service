package uk.gov.hmcts.cp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;
import uk.gov.hmcts.cp.services.AzureBlobService;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPublisher;
import uk.gov.hmcts.cp.services.JsonSchemaValidatorService;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;
import uk.gov.hmcts.cp.services.sanitization.HtmlStrippingSanitizer;
import uk.gov.hmcts.cp.services.sanitization.RequiredStringFieldsRegistry;
import uk.gov.hmcts.cp.services.sanitization.WafPatternSanitizer;
import uk.gov.hmcts.cp.services.sjp.SjpCourtListPublishService;
import uk.gov.hmcts.cp.services.sjp.SjpToCathPayloadTransformer;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class SjpPublishTaskTest {

    @Mock
    private CourtListStatusRepository repository;

    @Mock
    private CourtListPublisher courtListPublisher;

    @Mock
    private JsonSchemaValidatorService jsonSchemaValidatorService;

    @Mock
    private AzureBlobService azureBlobService;

    @Mock
    private ExecutionInfo executionInfo;

    private SjpPublishTask task;

    private static final List<Map<String, Object>> ONE_CASE = List.of(Map.of(
            "caseUrn", "URN1",
            "defendantName", "D",
            "prosecutorName", "P",
            "sjpOffences", List.of(Map.of("title", "t", "wording", "w"))));

    private static final DocumentSanitizer SANITIZER = new DocumentSanitizer(
            new WafPatternSanitizer("..\\.\\,../"),
            new HtmlStrippingSanitizer(),
            new RequiredStringFieldsRegistry());

    private UUID courtListId;

    @BeforeEach
    void setUp() {
        courtListId = UUID.randomUUID();
        task = new SjpPublishTask(
                repository,
                new SjpToCathPayloadTransformer(),
                courtListPublisher,
                SANITIZER,
                jsonSchemaValidatorService,
                Optional.of(azureBlobService));
    }

    private JsonObject jobData(UUID id, String listType, SjpListPayload payload, String language, String requestType) {
        try {
            String payloadJson = ObjectMapperConfig.getObjectMapper().writeValueAsString(payload);
            var builder = Json.createObjectBuilder()
                    .add(JobDataConstant.SJP_LIST_ID, id.toString())
                    .add(JobDataConstant.SJP_LIST_TYPE, listType)
                    .add(JobDataConstant.SJP_PAYLOAD, payloadJson);
            if (language != null) {
                builder.add(JobDataConstant.SJP_LANGUAGE, language);
            }
            if (requestType != null) {
                builder.add(JobDataConstant.SJP_REQUEST_TYPE, requestType);
            }
            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DtsMeta capturePublishedMeta() {
        ArgumentCaptor<DtsMeta> captor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(courtListPublisher).publish(anyString(), captor.capture());
        return captor.getValue();
    }

    // ── DtsMeta building (courtId, language, requestType) ───────────────────

    @Test
    void execute_usesCourtIdNumericOnDtsMeta_whenPresent() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "325");
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        assertThat(capturePublishedMeta().getCourtId()).isEqualTo("325");
    }

    @Test
    void execute_fallsBackToZeroOnDtsMeta_whenCourtIdNumericBlank() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "   ");
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        assertThat(capturePublishedMeta().getCourtId()).isEqualTo("0");
    }

    @Test
    void execute_setsLanguageToWelsh_whenPayloadIsWelshTrue() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, null, true);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        assertThat(capturePublishedMeta().getLanguage()).isEqualTo("WELSH");
    }

    @Test
    void execute_explicitLanguageOverridesIsWelsh() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, null, true);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, "ENGLISH", null));

        task.execute(executionInfo);

        assertThat(capturePublishedMeta().getLanguage()).isEqualTo("ENGLISH");
    }

    @Test
    void execute_passesRequestTypeToMeta_whenProvided() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, "FULL"));

        task.execute(executionInfo);

        assertThat(capturePublishedMeta().getRequestType()).isEqualTo("FULL");
    }

    @Test
    void execute_mapsPressListType() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PRESS_LIST, payload, null, null));

        task.execute(executionInfo);

        DtsMeta meta = capturePublishedMeta();
        assertThat(meta.getListType()).isEqualTo("SJP_PRESS_LIST");
        assertThat(meta.getSensitivity()).isEqualTo("CLASSIFIED");
    }

    // ── Delta list types: forwarded to CaTH verbatim, not collapsed to full ──

    @Test
    void execute_forwardsDeltaPublicListTypeToCaTH_insteadOfCollapsingToFull() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_DELTA_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        DtsMeta meta = capturePublishedMeta();
        assertThat(meta.getListType()).isEqualTo("SJP_DELTA_PUBLIC_LIST");
        assertThat(meta.getSensitivity()).isEqualTo("PUBLIC");
    }

    @Test
    void execute_forwardsDeltaPressListTypeToCaTH_andTreatsItAsPressForSensitivity() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_DELTA_PRESS_LIST, payload, null, null));

        task.execute(executionInfo);

        DtsMeta meta = capturePublishedMeta();
        assertThat(meta.getListType()).isEqualTo("SJP_DELTA_PRESS_LIST");
        assertThat(meta.getSensitivity()).isEqualTo("CLASSIFIED");
    }

    // ── blob upload (unique-uuid, before publish, shared naming with standard flow) ──

    @Test
    void execute_uploadsPayloadToBlobBeforePublishing_withBlobNameFromCaTHService() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        InOrder inOrder = inOrder(azureBlobService, courtListPublisher);
        inOrder.verify(azureBlobService).uploadJson(anyString(), org.mockito.ArgumentMatchers.eq(CaTHService.buildBlobName(courtListId)));
        inOrder.verify(courtListPublisher).publish(anyString(), any(DtsMeta.class));
    }

    @Test
    void execute_continuesPublishing_whenBlobUploadFails() {
        doThrow(new RuntimeException("blob failed")).when(azureBlobService).uploadJson(anyString(), anyString());
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        verify(courtListPublisher).publish(anyString(), any(DtsMeta.class));
    }

    @Test
    void execute_skipsBlobUpload_whenBlobServiceNotAvailable() {
        SjpPublishTask taskWithoutBlob = new SjpPublishTask(
                repository, new SjpToCathPayloadTransformer(), courtListPublisher, SANITIZER,
                jsonSchemaValidatorService, Optional.empty());
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        taskWithoutBlob.execute(executionInfo);

        verify(azureBlobService, never()).uploadJson(anyString(), anyString());
        verify(courtListPublisher).publish(anyString(), any(DtsMeta.class));
    }

    // ── repeat publish: always overwrites, no content-based dedup ───────────

    @Test
    void execute_alwaysRepublishes_whenTriggeredAgainForSameRow_evenWithIdenticalContent() {
        // Same as the standard flow: a repeat trigger for the same day/list type reuses and
        // overwrites the same row (SjpCourtListPublishService resets it to REQUESTED before
        // queuing) — the task always re-transforms, re-uploads and re-sends, no dedup-skip.
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId, null, Status.REQUESTED, null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, Instant.now());
        entity.setPublishDate(LocalDate.of(2025, 3, 9));
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        verify(azureBlobService).uploadJson(anyString(), anyString());
        verify(courtListPublisher).publish(anyString(), any(DtsMeta.class));
        assertThat(entity.getPublishStatus()).isEqualTo(Status.SUCCESSFUL);
    }

    @Test
    void execute_republishes_afterAPreviousFailure() {
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId, null, Status.FAILED, null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, Instant.now());
        entity.setPublishDate(LocalDate.of(2025, 3, 9));
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        verify(courtListPublisher).publish(anyString(), any(DtsMeta.class));
        assertThat(entity.getPublishStatus()).isEqualTo(Status.SUCCESSFUL);
    }

    // ── status updates ───────────────────────────────────────────────────────

    @Test
    void execute_marksFailed_whenCathReturnsNonSuccessStatus() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(500);
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId, null, Status.REQUESTED, null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, Instant.now());
        entity.setPublishDate(LocalDate.of(2025, 3, 9));
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        task.execute(executionInfo);

        assertThat(entity.getPublishStatus()).isEqualTo(Status.FAILED);
        assertThat(entity.getPublishErrorMessage()).contains("500");
    }

    @Test
    void execute_marksFailed_whenPublisherThrows() {
        doThrow(new RuntimeException("publish failed")).when(courtListPublisher).publish(anyString(), any(DtsMeta.class));
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId, null, Status.REQUESTED, null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, Instant.now());
        entity.setPublishDate(LocalDate.of(2025, 3, 9));
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        when(executionInfo.getJobData()).thenReturn(
                jobData(courtListId, SjpCourtListPublishService.SJP_PUBLIC_LIST, payload, null, null));

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(entity.getPublishStatus()).isEqualTo(Status.FAILED);
        assertThat(entity.getPublishErrorMessage()).contains("publish failed");
    }

    // ── missing job data ─────────────────────────────────────────────────────

    @Test
    void execute_returnsCompleted_whenJobDataNull() {
        when(executionInfo.getJobData()).thenReturn(null);

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListPublisher, never()).publish(anyString(), any(DtsMeta.class));
        verify(repository, never()).getByCourtListId(any());
    }

    @Test
    void execute_returnsCompleted_whenPayloadMissingFromJobData() {
        JsonObject jobData = Json.createObjectBuilder()
                .add(JobDataConstant.SJP_LIST_ID, courtListId.toString())
                .add(JobDataConstant.SJP_LIST_TYPE, SjpCourtListPublishService.SJP_PUBLIC_LIST)
                .build();
        when(executionInfo.getJobData()).thenReturn(jobData);

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListPublisher, never()).publish(anyString(), any(DtsMeta.class));
    }
}
