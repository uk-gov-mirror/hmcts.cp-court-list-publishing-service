package uk.gov.hmcts.cp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPdfHelper;
import uk.gov.hmcts.cp.services.CourtListQueryService;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.task.JobDataConstant;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class CourtListPublishAndPDFGenerationTaskTest {

    @Mock
    private CourtListStatusRepository repository;

    @Mock
    private CourtListQueryService courtListQueryService;

    @Mock
    private CaTHService cathService;

    @Mock
    private CourtListPdfHelper pdfHelper;

    @Mock
    private ExecutionInfo executionInfo;

    private CourtListPublishAndPDFGenerationTask task;

    private static final String TEST_USER_ID = "test-user-id";
    private UUID courtListId;
    private UUID courtCentreId;
    private CourtListStatusEntity entity;

    @BeforeEach
    void setUp() {
        courtListId = UUID.randomUUID();
        courtCentreId = UUID.randomUUID();
        entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.ONLINE_PUBLIC,
                Instant.now()
        );
        // Initialize task with mocked dependencies (CaTH publishing enabled for tests that verify CaTH call)
        task = new CourtListPublishAndPDFGenerationTask(
                repository,
                courtListQueryService,
                cathService,
                pdfHelper,
                true
        );
    }

    @Test
    void execute_shouldUpdateStatusToPublishSuccessful_whenValidCourtListIdProvided() {
        // Given
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(entity.getPublishStatus()).isEqualTo(Status.SUCCESSFUL);
        assertThat(entity.getLastUpdated()).isNotNull();
        verify(repository).getByCourtListId(courtListId);
        verify(repository).save(entity);
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenJobDataIsNull() {
        // Given
        when(executionInfo.getJobData()).thenReturn(null);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(repository, never()).getByCourtListId(any());
        verify(repository, never()).save(any());
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenCourtListIdIsMissingInJobData() {
        // Given
        JsonObject jobData = Json.createObjectBuilder()
                .add("courtCentreId", courtCentreId.toString())
                .add("courtListType", "PUBLIC")
                .build();
        when(executionInfo.getJobData()).thenReturn(jobData);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(repository, never()).getByCourtListId(any());
        verify(repository, never()).save(any());
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenCourtListIdIsNullInJobData() {
        // Given
        // JsonObjectBuilder doesn't allow null values, so we mock JsonObject to return null
        JsonObject mockJobData = org.mockito.Mockito.mock(JsonObject.class);
        when(mockJobData.getString(JobDataConstant.COURT_LIST_ID, null)).thenReturn(null);
        when(executionInfo.getJobData()).thenReturn(mockJobData);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(repository, never()).getByCourtListId(any());
        verify(repository, never()).save(any());
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenCourtListIdHasInvalidUuidFormat() {
        // Given
        JsonObject jobData = Json.createObjectBuilder()
                .add(JobDataConstant.COURT_LIST_ID, "invalid-uuid-format")
                .build();
        when(executionInfo.getJobData()).thenReturn(jobData);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(repository, never()).getByCourtListId(any());
        verify(repository, never()).save(any());
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenEntityNotFound() {
        // Given
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(null);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(repository).getByCourtListId(courtListId);
        verify(repository, never()).save(any());
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenRepositoryThrowsException() {
        // Given - repository throws when updating status to PUBLISH_SUCCESSFUL (task logs ERROR then continues)
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenThrow(new RuntimeException("Database error"));

        // Silence expected ERROR log from task so test output is clean
        Logger taskLogger = (Logger) LoggerFactory.getLogger(CourtListPublishAndPDFGenerationTask.class);
        Level originalLevel = taskLogger.getLevel();
        taskLogger.setLevel(Level.OFF);
        try {
            // When
            ExecutionInfo result = task.execute(executionInfo);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            verify(repository).getByCourtListId(courtListId);
            verify(repository, never()).save(any());
        } finally {
            taskLogger.setLevel(originalLevel);
        }
    }

    @Test
    void execute_shouldReturnCompletedStatus_whenSaveThrowsException() {
        // Given - save() throws when updating status (task logs ERROR then continues)
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);
        when(repository.save(entity)).thenThrow(new RuntimeException("Save error"));

        // Silence expected ERROR log from task so test output is clean
        Logger taskLogger = (Logger) LoggerFactory.getLogger(CourtListPublishAndPDFGenerationTask.class);
        Level originalLevel = taskLogger.getLevel();
        taskLogger.setLevel(Level.OFF);
        try {
            // When
            ExecutionInfo result = task.execute(executionInfo);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            verify(repository, atLeastOnce()).getByCourtListId(courtListId);
            verify(repository, atLeastOnce()).save(entity);
        } finally {
            taskLogger.setLevel(originalLevel);
        }
    }

    @Test
    void execute_shouldUpdateLastUpdatedTimestamp_whenStatusIsUpdated() {
        // Given
        Instant originalLastUpdated = Instant.now().minusSeconds(100);
        entity.setLastUpdated(originalLastUpdated);
        
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        // When
        task.execute(executionInfo);

        // Then
        assertThat(entity.getLastUpdated()).isAfter(originalLastUpdated);
        verify(repository).save(entity);
    }

    @Test
    void execute_shouldHandleEmptyJobData() {
        // Given
        JsonObject jobData = Json.createObjectBuilder().build();
        when(executionInfo.getJobData()).thenReturn(jobData);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(repository, never()).getByCourtListId(any());
        verify(repository, never()).save(any());
    }

    @Test
    void execute_shouldQueryCourtListAndSendToCaTH_whenValidJobDataProvided() {
        // Given
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        String publishDateStr = jobData.getString(JobDataConstant.PUBLISH_DATE);
        LocalDate publishDate = LocalDate.parse(publishDateStr);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        CourtListDocument courtListDocument = CourtListDocument.builder().build();
        when(courtListQueryService.getCourtListPayload(
                eq(CourtListType.ONLINE_PUBLIC),
                eq(courtCentreId.toString()),
                eq(publishDateStr),
                eq(publishDateStr),
                eq(TEST_USER_ID),
                anyBoolean()
        )).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(courtListDocument);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - getCourtListPayload is called twice: once for CaTH, once for PDF generation
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, times(1)).getCourtListPayload(
                CourtListType.ONLINE_PUBLIC,
                courtCentreId.toString(),
                publishDateStr,
                publishDateStr,
                TEST_USER_ID,
                true
        );

        verify(courtListQueryService, times(1)).getCourtListPayload(
                CourtListType.ONLINE_PUBLIC,
                courtCentreId.toString(),
                publishDateStr,
                publishDateStr,
                TEST_USER_ID,
                false
        );

        verify(courtListQueryService).buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC);
        verify(cathService).sendCourtListToCaTH(eq(courtListDocument), eq(CourtListType.ONLINE_PUBLIC), eq(publishDate), any(), any(), any());
        verify(repository).getByCourtListId(courtListId);
        verify(repository).save(entity);
    }

    @Test
    void execute_shouldNotSendToCaTH_whenCaTHPublishingDisabled() {
        // Given - task with CaTH publishing disabled
        CourtListPublishAndPDFGenerationTask taskWithCathDisabled = new CourtListPublishAndPDFGenerationTask(
                repository,
                courtListQueryService,
                cathService,
                pdfHelper,
                false
        );
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);

        // When
        ExecutionInfo result = taskWithCathDisabled.execute(executionInfo);

        // Then - task completes, CaTH was never called, publish status is not set to SUCCESSFUL
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(cathService, never()).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());
        verify(repository, never()).getByCourtListId(any());
        verify(repository, never()).save(any());
        assertThat(entity.getPublishStatus()).isEqualTo(Status.REQUESTED);
    }

    @Test
    void execute_shouldNotQueryCourtList_whenJobDataIsNull() {
        // Given
        when(executionInfo.getJobData()).thenReturn(null);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, never()).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(cathService, never()).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());
    }

    @Test
    void execute_shouldNotQueryCourtList_whenListIdIsMissing() {
        // Given
        JsonObject jobData = Json.createObjectBuilder()
                .add(JobDataConstant.COURT_LIST_ID, courtListId.toString())
                .add(JobDataConstant.COURT_CENTRE_ID, courtCentreId.toString())
                .build();
        when(executionInfo.getJobData()).thenReturn(jobData);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, never()).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(cathService, never()).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());
    }

    @Test
    void execute_shouldNotQueryCourtList_whenCourtCentreIdIsMissing() {
        // Given
        JsonObject jobData = Json.createObjectBuilder()
                .add(JobDataConstant.COURT_LIST_ID, courtListId.toString())
                .add(JobDataConstant.COURT_LIST_TYPE, "PUBLIC")
                .build();
        when(executionInfo.getJobData()).thenReturn(jobData);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, never()).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(cathService, never()).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());
    }

    @Test
    void execute_shouldHandleException_whenCourtListQueryServiceThrowsException() {
        // Given
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        when(courtListQueryService.getCourtListPayload(
                any(), any(), any(), any(), any(), anyBoolean()
        )).thenThrow(new RuntimeException("Query service error"));

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - getCourtListPayload called twice (CaTH and PDF paths), both throw
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, times(2)).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(cathService, never()).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());
        verify(repository).getByCourtListId(courtListId);
    }

    @Test
    void execute_shouldHandleException_whenCaTHServiceThrowsException() {
        // Given - CaTH throws so publish error is saved to DB
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        LocalDate publishDate = LocalDate.parse(jobData.getString(JobDataConstant.PUBLISH_DATE));
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        CourtListDocument courtListDocument = CourtListDocument.builder().build();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(courtListDocument);

        doThrow(new RuntimeException("CaTH service error"))
                .when(cathService).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - task completes; publish error saved to publish_error_message, publish status FAILED; updateStatusToPublishSuccessful not called
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, times(2)).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(courtListQueryService).buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC);
        verify(cathService).sendCourtListToCaTH(eq(courtListDocument), eq(CourtListType.ONLINE_PUBLIC), eq(publishDate), any(), any(), any());
        assertThat(entity.getPublishErrorMessage()).contains("CaTH service error", "RuntimeException");
        assertThat(entity.getPublishStatus()).isEqualTo(Status.FAILED);
        verify(repository, times(1)).getByCourtListId(courtListId);
        verify(repository, times(1)).save(entity);
    }

    @Test
    void execute_shouldStoreCaTHPublishErrorInPublishErrorMessageColumn_whenCaTHReturnsNon2xx() {
        // Given - CaTH returns 400 so exception is thrown and stored in publish_error_message
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        CourtListDocument courtListDocument = CourtListDocument.builder().build();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(courtListDocument);

        doThrow(new RuntimeException("CaTH publish failed with HTTP status 400: Invalid payload"))
                .when(cathService).sendCourtListToCaTH(any(), any(), any(LocalDate.class), any(), any(), any());

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - error stored in publish_error_message column, publish status FAILED (same as other errors)
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(entity.getPublishErrorMessage()).contains("CaTH publish failed", "400", "Invalid payload");
        assertThat(entity.getPublishStatus()).isEqualTo(Status.FAILED);
        verify(repository).save(entity);
    }

    @Test
    void execute_shouldSavePublishErrorMessageAndSetPublishStatusFailed_whenBuildCourtListDocumentThrows() {
        // Given - buildCourtListDocumentFromPayload throws (e.g. transform error)
        String errorMessage = "Transform failed";
        String causeMessage = "Invalid date format";
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenThrow(new RuntimeException(errorMessage, new IllegalStateException(causeMessage)));

        // When
        task.execute(executionInfo);

        // Then - publish error (full stack trace) saved, publish status FAILED
        assertThat(entity.getPublishErrorMessage())
                .contains(errorMessage, causeMessage, "RuntimeException", "IllegalStateException");
        assertThat(entity.getPublishStatus()).isEqualTo(Status.FAILED);
        verify(repository, times(1)).getByCourtListId(courtListId);
        verify(repository, times(1)).save(entity);
    }

    @Test
    void execute_shouldUsePublishDateForPayloadFetch() {
        // Given - task calls getCourtListPayload twice (CaTH with true, PDF with false); stub both with anyBoolean()
        String expectedDate = LocalDate.now().toString();
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(
                eq(CourtListType.ONLINE_PUBLIC),
                eq(courtCentreId.toString()),
                eq(expectedDate),
                eq(expectedDate),
                eq(TEST_USER_ID),
                anyBoolean()
        )).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(CourtListDocument.builder().build());

        // When
        task.execute(executionInfo);

        // Then - getCourtListPayload called twice with publishDate from jobData (CaTH and PDF)
        verify(courtListQueryService, times(2)).getCourtListPayload(
                eq(CourtListType.ONLINE_PUBLIC),
                eq(courtCentreId.toString()),
                eq(expectedDate),
                eq(expectedDate),
                eq(TEST_USER_ID),
                anyBoolean()
        );
    }

    @Test
    void execute_shouldGeneratePdf_whenPdfHelperIsAvailable() {
        // Given - payload is fetched twice (CaTH with isWelsh=true, PDF with isWelsh=false); stub both invocations
        String publishDate = LocalDate.now().toString();
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(
                eq(CourtListType.ONLINE_PUBLIC),
                eq(courtCentreId.toString()),
                eq(publishDate),
                eq(publishDate),
                eq(TEST_USER_ID),
                anyBoolean()
        )).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(CourtListDocument.builder().build());
        when(pdfHelper.generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC)).thenReturn(courtListId);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - getCourtListPayload called twice: once for CaTH, once for PDF
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, times(1)).getCourtListPayload(
                CourtListType.ONLINE_PUBLIC,
                courtCentreId.toString(),
                publishDate,
                publishDate,
                TEST_USER_ID,
                true
        );

        verify(courtListQueryService, times(1)).getCourtListPayload(
                CourtListType.ONLINE_PUBLIC,
                courtCentreId.toString(),
                publishDate,
                publishDate,
                TEST_USER_ID,
                false
        );

        verify(pdfHelper).generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC);
    }

    @Test
    void execute_shouldNotGeneratePdf_whenPayloadIsNull() {
        // Given - getCourtListPayload returns null (e.g. upstream failure)
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        when(courtListQueryService.getCourtListPayload(
                any(), any(), any(), any(), any(), anyBoolean()
        )).thenReturn(null);

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - getCourtListPayload called twice (CaTH and PDF paths); PDF not generated because payload is null
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, times(2)).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(pdfHelper, never()).generateAndUploadPdf(any(), any(), any());
    }

    @Test
    void execute_shouldContinue_whenPdfGenerationFails() {
        // Given - payload fetched once, PDF generation throws
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(CourtListDocument.builder().build());
        when(pdfHelper.generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC))
                .thenThrow(new RuntimeException("PDF generation error"));

        // When
        ExecutionInfo result = task.execute(executionInfo);

        // Then - task completes even if PDF generation fails
        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verify(courtListQueryService, times(2)).getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean());
        verify(pdfHelper).generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC);
        // Verify error message is saved to fileErrorMessage and file status is FAILED
        assertThat(entity.getFileErrorMessage()).contains("PDF generation error");
        assertThat(entity.getFileStatus()).isEqualTo(Status.FAILED);
        // save called twice: updateStatusToPublishSuccessful then updateFileErrorMessage
        verify(repository, times(2)).save(entity);
    }

    @Test
    void execute_shouldSaveFileErrorMessageAndSetFileStatusFailed_whenPdfGenerationThrows() {
        // Given - PDF generation throws with message and cause
        String errorMessage = "Upload failed";
        String causeMessage = "Connection timeout";
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(CourtListDocument.builder().build());
        when(pdfHelper.generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC))
                .thenThrow(new RuntimeException(errorMessage, new IllegalStateException(causeMessage)));

        // When
        task.execute(executionInfo);

        // Then - entity is updated with full stack trace and FAILED status
        assertThat(entity.getFileErrorMessage())
                .contains(errorMessage, causeMessage, "RuntimeException", "IllegalStateException");
        assertThat(entity.getFileStatus()).isEqualTo(Status.FAILED);
        verify(repository, times(2)).save(entity);
    }

    @ParameterizedTest
    @EnumSource(value = CourtListType.class, names = {"PUBLIC", "STANDARD", "BENCH"})
    void execute_shouldGeneratePdfAndSetFileId_forProgressionPdfType(CourtListType progressionType) {
        JsonObject jobData = Json.createObjectBuilder()
                .add(JobDataConstant.COURT_LIST_ID, courtListId.toString())
                .add(JobDataConstant.COURT_CENTRE_ID, courtCentreId.toString())
                .add(JobDataConstant.COURT_LIST_TYPE, progressionType.name())
                .add(JobDataConstant.PUBLISH_DATE, LocalDate.now().toString())
                .add(JobDataConstant.USER_ID, TEST_USER_ID)
                .build();
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, progressionType))
                .thenReturn(CourtListDocument.builder().build());
        when(pdfHelper.generateAndUploadPdf(payload, courtListId, progressionType)).thenReturn(courtListId);

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result).isNotNull();
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);

        verify(pdfHelper).generateAndUploadPdf(payload, courtListId, progressionType);
        assertThat(entity.getFileStatus()).isEqualTo(Status.SUCCESSFUL);
        assertThat(entity.getFileId()).isEqualTo(courtListId);
    }

    @Test
    void execute_shouldIncrementPublishCount_whenFileStatusBecomesSuccessful() {
        // Given
        entity.setPublishCount(2);
        String publishDate = LocalDate.now().toString();
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(CourtListDocument.builder().build());
        when(pdfHelper.generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC)).thenReturn(courtListId);

        // When
        task.execute(executionInfo);

        // Then
        assertThat(entity.getFileStatus()).isEqualTo(Status.SUCCESSFUL);
        assertThat(entity.getPublishCount()).isEqualTo(3);
    }

    @Test
    void execute_shouldNotIncrementPublishCount_whenFileStatusFails() {
        // Given
        entity.setPublishCount(1);
        JsonObject jobData = createJobDataWithCourtListId(courtListId);
        when(executionInfo.getJobData()).thenReturn(jobData);
        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        CourtListPayload payload = new CourtListPayload();
        when(courtListQueryService.getCourtListPayload(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(payload);
        when(courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC))
                .thenReturn(CourtListDocument.builder().build());
        when(pdfHelper.generateAndUploadPdf(payload, courtListId, CourtListType.ONLINE_PUBLIC))
                .thenThrow(new RuntimeException("PDF error"));

        // When
        task.execute(executionInfo);

        // Then
        assertThat(entity.getFileStatus()).isEqualTo(Status.FAILED);
        assertThat(entity.getPublishCount()).isEqualTo(1);
    }

    private JsonObject createJobDataWithCourtListId(UUID courtListId) {
        return Json.createObjectBuilder()
                .add(JobDataConstant.COURT_LIST_ID, courtListId.toString())
                .add(JobDataConstant.COURT_CENTRE_ID, courtCentreId.toString())
                .add(JobDataConstant.COURT_LIST_TYPE, "ONLINE_PUBLIC")
                .add(JobDataConstant.PUBLISH_DATE, LocalDate.now().toString())
                .add(JobDataConstant.USER_ID, TEST_USER_ID)
                .build();
    }
}
