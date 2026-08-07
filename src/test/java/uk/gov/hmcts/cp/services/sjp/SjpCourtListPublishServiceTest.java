package uk.gov.hmcts.cp.services.sjp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SjpCourtListPublishServiceTest {

    @Mock
    private CourtListStatusRepository repository;

    @Mock
    private SjpTaskTriggerService sjpTaskTriggerService;

    private SjpCourtListPublishService service;

    /** Minimal valid readyCases entry */
    private static final List<Map<String, Object>> ONE_CASE = List.of(Map.of(
            "caseUrn", "URN1",
            "defendantName", "D",
            "prosecutorName", "P",
            "sjpOffences", List.of(Map.of("title", "t", "wording", "w"))));

    @BeforeEach
    void setUp() {
        service = new SjpCourtListPublishService(repository, sjpTaskTriggerService, true);
        lenient().when(repository.findByCourtCentreIdIsNullAndPublishDateAndCourtListType(any(LocalDate.class), anyString()))
                .thenReturn(Optional.empty());
    }

    // ── cath publishing disabled ─────────────────────────────────────────────

    @Test
    void publishSjpCourtList_returnsAccepted_whenCathPublishingDisabled() {
        SjpCourtListPublishService disabledService =
                new SjpCourtListPublishService(repository, sjpTaskTriggerService, false);

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        SjpCourtListPublishService.SjpPublishResult result =
                disabledService.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        assertThat(result.getMessage()).contains("disabled");
        verify(sjpTaskTriggerService, never()).triggerSjpPublishTask(
                any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).save(any());
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
        verify(sjpTaskTriggerService, never()).triggerSjpPublishTask(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publishSjpCourtList_returnsFailed_whenListPayloadNotConvertible() {
        SjpCourtListPublishService.SjpPublishResult result =
                service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, "not-a-payload-object");

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("Invalid listPayload");
    }

    // ── queuing behaviour ────────────────────────────────────────────────────

    @Test
    void publishSjpCourtList_queuesTask_andReturnsAccepted_whenPayloadValid() {
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "325");

        SjpCourtListPublishService.SjpPublishResult result =
                service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, "FULL", payload);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        verify(sjpTaskTriggerService).triggerSjpPublishTask(
                any(UUID.class), eq("325"), eq(SjpCourtListPublishService.SJP_PUBLIC_LIST),
                eq(LocalDate.of(2025, 3, 9)), eq(null), eq("FULL"), anyString());
    }

    @Test
    void publishSjpCourtList_normalizesCourtIdToZero_whenCourtIdNumericBlank() {
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "   ");

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        verify(sjpTaskTriggerService).triggerSjpPublishTask(
                any(UUID.class), eq("0"), anyString(), any(LocalDate.class), any(), any(), anyString());
    }

    @Test
    void publishSjpCourtList_reusesExistingCourtListId_whenDedupKeyMatches() {
        UUID existingId = UUID.randomUUID();
        CourtListStatusEntity existing = new CourtListStatusEntity(
                existingId, null, Status.SUCCESSFUL, null,
                SjpCourtListPublishService.SJP_PUBLIC_LIST, Instant.now());
        existing.setPublishDate(LocalDate.of(2025, 3, 9));
        when(repository.findByCourtCentreIdIsNullAndPublishDateAndCourtListType(
                LocalDate.of(2025, 3, 9), SjpCourtListPublishService.SJP_PUBLIC_LIST))
                .thenReturn(Optional.of(existing));

        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "325");
        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        verify(sjpTaskTriggerService).triggerSjpPublishTask(
                eq(existingId), eq("325"), anyString(), any(LocalDate.class), any(), any(), anyString());
        assertThat(existing.getPublishStatus()).isEqualTo(Status.REQUESTED);
        verify(repository).save(existing);
    }

    @Test
    void publishSjpCourtList_createsNewCourtListId_whenNoDedupMatch() {
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE, "325");
        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        ArgumentCaptor<CourtListStatusEntity> entityCaptor = ArgumentCaptor.forClass(CourtListStatusEntity.class);
        verify(repository).save(entityCaptor.capture());
        CourtListStatusEntity saved = entityCaptor.getValue();

        verify(sjpTaskTriggerService).triggerSjpPublishTask(
                eq(saved.getCourtListId()), eq("325"), anyString(), any(LocalDate.class), any(), any(), anyString());
        assertThat(saved.getPublishStatus()).isEqualTo(Status.REQUESTED);
        assertThat(saved.getCourtCentreId()).isNull();
        assertThat(saved.getFileStatus()).isNull();
        assertThat(saved.getCourtListType()).isEqualTo(SjpCourtListPublishService.SJP_PUBLIC_LIST);
        assertThat(saved.getPublishDate()).isEqualTo(LocalDate.of(2025, 3, 9));
    }

    @Test
    void publishSjpCourtList_derivesPublishDate_fromDateOnlyGeneratedDateAndTime() {
        SjpListPayload payload = new SjpListPayload("2025-03-09", ONE_CASE);
        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        verify(repository).findByCourtCentreIdIsNullAndPublishDateAndCourtListType(
                eq(LocalDate.of(2025, 3, 9)), anyString());
    }

    @Test
    void publishSjpCourtList_returnsFailed_whenTaskTriggerThrows() {
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", ONE_CASE);
        org.mockito.Mockito.doThrow(new RuntimeException("queue unavailable"))
                .when(sjpTaskTriggerService).triggerSjpPublishTask(
                        any(), any(), any(), any(), any(), any(), any());

        SjpCourtListPublishService.SjpPublishResult result =
                service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLIC_LIST, null, null, payload);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("Failed to queue SJP court list for publishing");
    }
}
