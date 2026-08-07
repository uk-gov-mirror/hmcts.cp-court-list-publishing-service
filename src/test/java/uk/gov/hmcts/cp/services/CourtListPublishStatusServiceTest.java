package uk.gov.hmcts.cp.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListPublishResponse;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CourtListPublishStatusServiceTest {

    @Mock
    private CourtListStatusRepository repository;

    @InjectMocks
    private CourtListPublishStatusService service;

    @Test
    void getByCourtListId_shouldReturnResponse_whenEntityExists() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.PUBLIC.name(),
                Instant.now()
        );

        when(repository.getByCourtListId(courtListId)).thenReturn(entity);

        // When
        CourtListPublishResponse result = service.getByCourtListId(courtListId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getCourtListId()).isEqualTo(courtListId);
        assertThat(result.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(result.getPublishStatus()).isEqualTo(Status.REQUESTED);
        assertThat(result.getCourtListType()).isEqualTo(CourtListType.PUBLIC);
        verify(repository).getByCourtListId(courtListId);
    }

    @Test
    void getByCourtListId_shouldThrowResponseStatusException_whenEntityDoesNotExist() {
        // Given
        UUID courtListId = UUID.randomUUID();

        when(repository.getByCourtListId(courtListId)).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> service.getByCourtListId(courtListId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                });

        verify(repository).getByCourtListId(courtListId);
    }

    @Test
    void getByCourtListId_shouldThrowResponseStatusException_whenCourtListIdIsNull() {
        // When & Then
        assertThatThrownBy(() -> service.getByCourtListId(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).getByCourtListId(any());
    }

    @Test
    void createOrUpdate_shouldCreateNewEntity_whenEntityDoesNotExist() {
        // Given
        UUID courtCentreId = UUID.randomUUID();
        CourtListType courtListType = CourtListType.STANDARD;
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now();

        when(repository.findByCourtCentreIdAndPublishDateAndCourtListType(courtCentreId, startDate, courtListType.name()))
                .thenReturn(Optional.empty());
        when(repository.save(any(CourtListStatusEntity.class))).thenAnswer(invocation -> invocation.<CourtListStatusEntity>getArgument(0));

        // When
        CourtListPublishResponse result = service.createOrUpdate(
                courtCentreId, courtListType, startDate, endDate);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getCourtListId()).isNotNull();
        assertThat(result.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(result.getPublishStatus()).isEqualTo(Status.REQUESTED);
        assertThat(result.getCourtListType()).isEqualTo(courtListType);
        assertThat(result.getLastUpdated()).isNotNull();
        verify(repository).findByCourtCentreIdAndPublishDateAndCourtListType(courtCentreId, startDate, courtListType.name());
        verify(repository).save(any(CourtListStatusEntity.class));
    }

    @Test
    void createOrUpdate_shouldUpdateExistingEntity_whenEntityExists() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        CourtListType courtListType = CourtListType.ALPHABETICAL;
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now();

        CourtListStatusEntity existingEntity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                Status.SUCCESSFUL,
                Status.REQUESTED,
                courtListType.name(),
                Instant.now()
        );
        existingEntity.setPublishDate(startDate);

        when(repository.findByCourtCentreIdAndPublishDateAndCourtListType(courtCentreId, startDate, courtListType.name()))
                .thenReturn(Optional.of(existingEntity));
        when(repository.save(existingEntity)).thenReturn(existingEntity);

        // When
        CourtListPublishResponse result = service.createOrUpdate(
                courtCentreId, courtListType, startDate, endDate);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getCourtListId()).isEqualTo(courtListId);
        assertThat(result.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(result.getPublishStatus()).isEqualTo(Status.REQUESTED);
        assertThat(result.getCourtListType()).isEqualTo(courtListType);
        assertThat(result.getLastUpdated()).isNotNull();
        verify(repository).findByCourtCentreIdAndPublishDateAndCourtListType(courtCentreId, startDate, courtListType.name());
        verify(repository).save(existingEntity);
    }

    @Test
    void createOrUpdate_shouldThrowResponseStatusException_whenCourtCentreIdIsNull() {
        // When & Then
        assertThatThrownBy(() -> service.createOrUpdate(
                null, CourtListType.FINAL, LocalDate.now(), LocalDate.now()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).save(any());
    }

    @Test
    void createOrUpdate_shouldThrowResponseStatusException_whenCourtListTypeIsNull() {
        // When & Then
        assertThatThrownBy(() -> service.createOrUpdate(
                UUID.randomUUID(), null, LocalDate.now(), LocalDate.now()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).save(any());
    }

    @Test
    void createOrUpdate_shouldThrowResponseStatusException_whenStartDateIsNull() {
        // When & Then
        assertThatThrownBy(() -> service.createOrUpdate(
                UUID.randomUUID(), CourtListType.FIRM, null, LocalDate.now()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).save(any());
    }

    @Test
    void createOrUpdate_shouldThrowResponseStatusException_whenEndDateIsNull() {
        // When & Then
        assertThatThrownBy(() -> service.createOrUpdate(
                UUID.randomUUID(), CourtListType.PUBLIC, LocalDate.now(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).save(any());
    }

    @Test
    void createOrUpdate_shouldThrowResponseStatusException_whenStartDateDoesNotEqualEndDate() {
        // When & Then
        assertThatThrownBy(() -> service.createOrUpdate(
                UUID.randomUUID(), CourtListType.STANDARD, LocalDate.now(), LocalDate.now().plusDays(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).save(any());
    }

    @Test
    void findPublishStatus_shouldReturnList_whenQueryingByCourtCentreIdAndPublishDate() {
        // Given
        UUID courtCentreId = UUID.randomUUID();
        LocalDate publishDate = LocalDate.now();
        CourtListStatusEntity entity1 = new CourtListStatusEntity(
                UUID.randomUUID(),
                courtCentreId,
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.PUBLIC.name(),
                Instant.now()
        );
        entity1.setPublishDate(publishDate);
        CourtListStatusEntity entity2 = new CourtListStatusEntity(
                UUID.randomUUID(),
                courtCentreId,
                Status.SUCCESSFUL,
                Status.REQUESTED,
                CourtListType.STANDARD.name(),
                Instant.now()
        );
        entity2.setPublishDate(publishDate);

        when(repository.findByCourtCentreIdAndPublishDate(courtCentreId, publishDate))
                .thenReturn(List.of(entity1, entity2));

        // When
        List<CourtListPublishResponse> result = service.findPublishStatus(
                null, courtCentreId, publishDate, null);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(CourtListPublishResponse::getCourtCentreId)
                .containsExactlyInAnyOrder(courtCentreId, courtCentreId);
        assertThat(result).extracting(CourtListPublishResponse::getPublishStatus)
                .containsExactlyInAnyOrder(Status.SUCCESSFUL, Status.REQUESTED);
        verify(repository).findByCourtCentreIdAndPublishDate(courtCentreId, publishDate);
    }

    @Test
    void findPublishStatus_shouldReturnEmptyList_whenNoEntitiesFound() {
        // Given
        UUID courtCentreId = UUID.randomUUID();
        LocalDate publishDate = LocalDate.now();

        when(repository.findByCourtCentreIdAndPublishDate(courtCentreId, publishDate))
                .thenReturn(List.of());

        // When
        List<CourtListPublishResponse> result = service.findPublishStatus(
                null, courtCentreId, publishDate, null);

        // Then
        assertThat(result).isEmpty();
        verify(repository).findByCourtCentreIdAndPublishDate(courtCentreId, publishDate);
    }

    @Test
    void findPublishStatus_shouldThrowResponseStatusException_whenCourtCentreIdIsNull() {
        // When & Then
        assertThatThrownBy(() -> service.findPublishStatus(null, null, LocalDate.now(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex = (ResponseStatusException) exception;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });

        verify(repository, never()).findByCourtCentreIdAndPublishDate(any(), any());
    }

    @Test
    void findAll_shouldReturnAllEntities() {
        // Given
        CourtListStatusEntity entity1 = new CourtListStatusEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.STANDARD.name(),
                Instant.now()
        );
        CourtListStatusEntity entity2 = new CourtListStatusEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.SUCCESSFUL,
                Status.REQUESTED,
                CourtListType.PUBLIC.name(),
                Instant.now()
        );

        when(repository.findByCourtCentreIdIsNotNull()).thenReturn(List.of(entity1, entity2));

        // When
        List<CourtListPublishResponse> result = service.findAll();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(CourtListPublishResponse::getPublishStatus)
                .containsExactlyInAnyOrder(Status.REQUESTED, Status.SUCCESSFUL);
        assertThat(result).extracting(CourtListPublishResponse::getCourtListType)
                .containsExactlyInAnyOrder(CourtListType.fromValue("STANDARD"), CourtListType.fromValue("PUBLIC"));
        verify(repository).findByCourtCentreIdIsNotNull();
    }

    @Test
    void findAll_excludesSjpRows_becauseTheyHaveNoCourtCentreId() {
        // Given — SJP rows (courtCentreId null) are excluded at the repository query level,
        // so findAll() never has to convert an SJP courtListType value back to CourtListType.
        CourtListStatusEntity standardEntity = new CourtListStatusEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.SUCCESSFUL,
                Status.SUCCESSFUL,
                CourtListType.ONLINE_PUBLIC.name(),
                Instant.now()
        );

        when(repository.findByCourtCentreIdIsNotNull()).thenReturn(List.of(standardEntity));

        // When
        List<CourtListPublishResponse> result = service.findAll();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getCourtListType()).isEqualTo(CourtListType.ONLINE_PUBLIC);
        verify(repository).findByCourtCentreIdIsNotNull();
        verify(repository, org.mockito.Mockito.never()).findAll();
    }
}

