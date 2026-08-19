package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SJP publishes must each get their own publish-status row. SJP is national, so court_centre_id
 * is NULL and every row shares the publish date - which makes the fused CourtListType the only
 * thing keeping the eight daily publishes apart.
 */
@ExtendWith(MockitoExtension.class)
class SjpPublishStatusRecordingTest {

    private static final LocalDate PUBLISH_DATE = LocalDate.of(2026, 8, 19);

    @Mock
    private CourtListStatusRepository repository;

    @InjectMocks
    private CourtListPublishStatusService service;

    @Test
    void recordsSuccessfulPublishAgainstTheFusedCourtListType() {
        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PRESS_DELTA_WELSH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        CourtListStatusEntity saved = captureSaved();
        assertThat(saved.getCourtListType()).isEqualTo(CourtListType.SJP_PRESS_DELTA_WELSH);
        assertThat(saved.getPublishStatus()).isEqualTo(Status.SUCCESSFUL);
        assertThat(saved.getPublishDate()).isEqualTo(PUBLISH_DATE);
    }

    @Test
    void recordsFailureWithTheErrorMessage() {
        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, PUBLISH_DATE, Status.FAILED, "CaTH returned status 500");

        CourtListStatusEntity saved = captureSaved();
        assertThat(saved.getPublishStatus()).isEqualTo(Status.FAILED);
        assertThat(saved.getPublishErrorMessage()).isEqualTo("CaTH returned status 500");
    }

    @Test
    void leavesCourtCentreIdNullBecauseSjpIsNational() {
        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        assertThat(captureSaved().getCourtCentreId()).isNull();
    }

    @Test
    void leavesFileStatusNullBecauseSjpProducesNoFile() {
        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        assertThat(captureSaved().getFileStatus()).isNull();
    }

    @Test
    void looksUpTheExistingRowByTheFusedCourtListType() {
        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PRESS_FULL_WELSH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        verify(repository).findByPublishDateAndCourtListType(
                eq(PUBLISH_DATE),
                eq(CourtListType.SJP_PRESS_FULL_WELSH));
    }

    @Test
    void updatesTheExistingRowRatherThanCreatingADuplicateForTheSameDayAndType() {
        UUID existingId = UUID.randomUUID();
        CourtListStatusEntity existing = new CourtListStatusEntity(
                existingId,
                null,
                Status.REQUESTED,
                null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH,
                Instant.now());
        existing.setPublishDate(PUBLISH_DATE);

        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        CourtListStatusEntity saved = captureSaved();
        assertThat(saved.getCourtListId()).isEqualTo(existingId);
        assertThat(saved.getPublishStatus()).isEqualTo(Status.SUCCESSFUL);
    }

    @Test
    void clearsAStalePublishErrorMessageOnASubsequentSuccess() {
        CourtListStatusEntity existing = new CourtListStatusEntity(
                UUID.randomUUID(),
                null,
                Status.FAILED,
                null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH,
                Instant.now());
        existing.setPublishDate(PUBLISH_DATE);
        existing.setPublishErrorMessage("yesterday's failure");

        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        assertThat(captureSaved().getPublishErrorMessage()).isNull();
    }

    @Test
    void countsEachPublishSoRepeatTriggersAreVisible() {
        CourtListStatusEntity existing = new CourtListStatusEntity(
                UUID.randomUUID(),
                null,
                Status.SUCCESSFUL,
                null,
                CourtListType.SJP_PUBLIC_FULL_ENGLISH,
                Instant.now());
        existing.setPublishDate(PUBLISH_DATE);
        existing.setPublishCount(1);

        when(repository.findByPublishDateAndCourtListType(any(), any()))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSjpPublish(
                CourtListType.SJP_PUBLIC_FULL_ENGLISH, PUBLISH_DATE, Status.SUCCESSFUL, null);

        assertThat(captureSaved().getPublishCount()).isEqualTo(2);
    }

    @Test
    void rejectsANonSjpCourtListType() {
        assertThat(
                org.assertj.core.api.Assertions.catchThrowable(() ->
                        service.recordSjpPublish(CourtListType.STANDARD, PUBLISH_DATE, Status.SUCCESSFUL, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CourtListStatusEntity captureSaved() {
        ArgumentCaptor<CourtListStatusEntity> captor = ArgumentCaptor.forClass(CourtListStatusEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
