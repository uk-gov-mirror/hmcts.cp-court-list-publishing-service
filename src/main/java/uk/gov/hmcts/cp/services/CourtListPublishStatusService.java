package uk.gov.hmcts.cp.services;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListPublishResponse;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CourtListPublishStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(CourtListPublishStatusService.class);
    private static final String ERROR_COURT_LIST_ID_REQUIRED = "Court list ID is required";
    private static final String ERROR_COURT_CENTRE_ID_REQUIRED = "Court centre ID is required";
    private static final String ERROR_PUBLISH_STATUS_REQUIRED = "Status is required";
    private static final String ERROR_COURT_LIST_TYPE_REQUIRED = "Court list type is required";
    private static final String ERROR_ENTITY_NOT_FOUND = "Court list publish status not found";
    private static final String ERROR_START_END_DATE_MISMATCH = "Start date and end date must be the same";

    private final CourtListStatusRepository repository;

    @Transactional
    public CourtListPublishResponse getByCourtListId(final UUID courtListId) {
        validateCourtListId(courtListId);
        LOG.atDebug().log("Fetching court list publish status for ID: {}", courtListId);

        CourtListStatusEntity entity = repository.getByCourtListId(courtListId);
        if (entity == null) {
            LOG.atWarn().log("Court list publish status not found for ID: {}", courtListId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_ENTITY_NOT_FOUND);
        }
        return toResponse(entity);
    }

    @Transactional
    public CourtListPublishResponse createOrUpdate(
            final UUID courtCentreId,
            final CourtListType courtListType,
            final LocalDate startDate,
            final LocalDate endDate) {
        validateCourtCentreId(courtCentreId);
        validateCourtListType(courtListType);
        validateStartAndEndDate(startDate, endDate);

        final LocalDate publishDate = startDate; // Use startDate as publishDate since they must be equal

        LOG.atDebug().log("Creating or updating court list publish status for court centre ID: {}, type: {}, date: {}",
                courtCentreId, courtListType, publishDate);

        // Search for existing entity by courtCentreId, publishDate, and courtListType
        Optional<CourtListStatusEntity> existingEntityOpt = repository.findByCourtCentreIdAndPublishDateAndCourtListType(
                courtCentreId, publishDate, courtListType);

        CourtListStatusEntity entity;
        if (existingEntityOpt.isPresent()) {
            entity = existingEntityOpt.get();
            LOG.atDebug().log("Found existing court list publish status with ID: {}, updating with status REQUESTED", entity.getCourtListId());
            updateExistingEntity(entity);
        } else {
            LOG.atDebug().log("No existing court list publish status found, creating new one");
            final UUID courtListId = UUID.randomUUID();
            entity = createNewEntity(courtListId, courtCentreId, courtListType, publishDate);
        }

        CourtListStatusEntity savedEntity = repository.save(entity);
        return toResponse(savedEntity);
    }

    @Transactional
    public List<CourtListPublishResponse> findPublishStatus(
            final UUID courtListId,
            final UUID courtCentreId,
            final LocalDate publishDate,
            final CourtListType courtListType) {
        // Validate that either courtListId is provided, or both courtCentreId and publishDate are provided
        if (courtListId != null) {
            // Query by courtListId
            validateCourtListId(courtListId);
            LOG.atDebug().log("Fetching court list publish status by court list ID: {}", courtListId);
            CourtListStatusEntity entity = repository.getByCourtListId(courtListId);
            if (entity == null) {
                LOG.atDebug().log("Court list publish status not found for court list ID: {}, returning empty collection", courtListId);
                return List.of();
            }
            return List.of(toResponse(entity));
        }

        // Query by courtCentreId and publishDate (with optional courtListType)
        validateCourtCentreId(courtCentreId);
        if (publishDate == null) {
            LOG.atWarn().log("No publish date provided");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either courtListId must be provided, or both courtCentreId and publishDate must be provided");
        }

        LOG.atDebug().log("Fetching court list publish statuses for court centre ID: {}, publish date: {}, type: {}",
                courtCentreId, publishDate, courtListType);

        List<CourtListStatusEntity> entities;
        if (courtListType != null) {
            // Filter by courtListType if provided
            Optional<CourtListStatusEntity> entityOpt = repository.findByCourtCentreIdAndPublishDateAndCourtListType(
                    courtCentreId, publishDate, courtListType);
            entities = entityOpt.map(List::of).orElse(List.of());
        } else {
            // Get all for courtCentreId and publishDate
            entities = repository.findByCourtCentreIdAndPublishDate(courtCentreId, publishDate);
        }

        if (entities.isEmpty()) {
            LOG.atDebug().log("Court list publish statuses not found for court centre ID: {}, publish date: {}, type: {}, returning empty collection",
                    courtCentreId, publishDate, courtListType);
            return List.of();
        }

        return entities.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }


    /** Excludes SJP rows (courtCentreId is always null for those) — this is the standard flow's listing. */
    @Transactional
    public List<CourtListPublishResponse> findAll() {
        LOG.atDebug().log("Fetching all court list publish statuses");
        return repository.findByCourtCentreIdIsNotNull().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Validation helper methods

    private void validateCourtListId(final UUID courtListId) {
        if (Objects.isNull(courtListId)) {
            LOG.atWarn().log("No court list id provided");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_COURT_LIST_ID_REQUIRED);
        }
    }

    private void validateCourtCentreId(final UUID courtCentreId) {
        if (Objects.isNull(courtCentreId)) {
            LOG.atWarn().log("No court centre id provided");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_COURT_CENTRE_ID_REQUIRED);
        }
    }

    private void validateCourtListType(final CourtListType courtListType) {
        if (courtListType == null) {
            LOG.atWarn().log("No court list type provided");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_COURT_LIST_TYPE_REQUIRED);
        }
    }

    private void validateStartAndEndDate(final LocalDate startDate, final LocalDate endDate) {
        if (startDate == null || endDate == null) {
            LOG.atWarn().log("Start date or end date is null");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start date and end date are required");
        }
        if (!startDate.equals(endDate)) {
            LOG.atWarn().log("Start date {} does not match end date {}", startDate, endDate);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_START_END_DATE_MISMATCH);
        }
    }

    // Entity helper methods

    private void updateExistingEntity(final CourtListStatusEntity entity) {
        entity.setPublishStatus(Status.REQUESTED);
        entity.setFileStatus(Status.REQUESTED);
        entity.setFileUrl(null);
        entity.setFileId(null);
        entity.setFileErrorMessage(null);
        entity.setPublishErrorMessage(null);
        entity.setLastUpdated(Instant.now());
    }

    private CourtListStatusEntity createNewEntity(
            final UUID courtListId,
            final UUID courtCentreId,
            final CourtListType courtListType,
            final LocalDate publishDate) {
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                Status.REQUESTED, // Initial publish status
                Status.REQUESTED, // Default file status for new entities
                courtListType,
                Instant.now()
        );
        entity.setPublishDate(publishDate);
        return entity;
    }

    // Mapper method to convert entity to response
    private CourtListPublishResponse toResponse(final CourtListStatusEntity entity) {
        OffsetDateTime lastUpdated = entity.getLastUpdated() != null
                ? entity.getLastUpdated().atOffset(ZoneOffset.UTC)
                : null;

        if (entity.getCourtListType() == null) {
            LOG.atError().log("Court list type is null for entity with courtListId: {}", entity.getCourtListId());
            throw new IllegalStateException("Court list type is required and cannot be null");
        }

        // Convert String publishStatus to Status enum
        Status publishStatusEnum = entity.getPublishStatus();
        Status fileStatusEnum = entity.getFileStatus();

        return new CourtListPublishResponse(
                entity.getCourtListId(),
                entity.getCourtCentreId(),
                publishStatusEnum,
                fileStatusEnum,
                entity.getCourtListType(),
                lastUpdated,
                entity.getFileUrl(),
                entity.getFileId(),
                entity.getPublishErrorMessage(),
                entity.getFileErrorMessage(),
                entity.getPublishDate()
        );
    }
}

