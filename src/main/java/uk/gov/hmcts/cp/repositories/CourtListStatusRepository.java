package uk.gov.hmcts.cp.repositories;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CourtListStatusRepository extends JpaRepository<CourtListStatusEntity, UUID> {

    CourtListStatusEntity getByCourtListId(UUID courtListId);

    List<CourtListStatusEntity> findByCourtCentreId(UUID courtCentreId);

    List<CourtListStatusEntity> findByPublishStatus(Status publishStatus);

    List<CourtListStatusEntity> findByCourtCentreIdAndPublishStatus(UUID courtCentreId, Status publishStatus);

    Optional<CourtListStatusEntity> findByCourtCentreIdAndPublishDateAndCourtListType(
            UUID courtCentreId, LocalDate publishDate, CourtListType courtListType);

    /**
     * Locates an SJP publish-status row. SJP is national so court_centre_id is NULL, which a
     * derived query cannot match with an equality predicate - and it adds nothing to the key
     * anyway, because the SJP_* court list types are SJP-only and already fuse audience,
     * request type and language.
     */
    Optional<CourtListStatusEntity> findByPublishDateAndCourtListType(
            LocalDate publishDate, CourtListType courtListType);

    List<CourtListStatusEntity> findByCourtCentreIdAndPublishDate(
            UUID courtCentreId, LocalDate publishDate);

    /** Rows belonging to the standard/online-public flow only (excludes SJP rows, which have no courtCentreId). */
    List<CourtListStatusEntity> findByCourtCentreIdIsNotNull();

    @Query("SELECT e FROM CourtListStatusEntity e WHERE e.publishDate < :cutoff")
    List<CourtListStatusEntity> findByPublishDateBefore(@Param("cutoff") LocalDate cutoff);

}
