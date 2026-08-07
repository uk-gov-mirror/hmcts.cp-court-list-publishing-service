package uk.gov.hmcts.cp.repositories;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
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
            UUID courtCentreId, LocalDate publishDate, String courtListType);

    List<CourtListStatusEntity> findByCourtCentreIdAndPublishDate(
            UUID courtCentreId, LocalDate publishDate);

    /** SJP dedup lookup: SJP has no court-centre concept, so courtCentreId is always null for these rows. */
    Optional<CourtListStatusEntity> findByCourtCentreIdIsNullAndPublishDateAndCourtListType(
            LocalDate publishDate, String courtListType);

    /** Rows belonging to the standard/online-public flow only (excludes SJP rows, which have no courtCentreId). */
    List<CourtListStatusEntity> findByCourtCentreIdIsNotNull();

    @Query("SELECT e FROM CourtListStatusEntity e WHERE e.publishDate < :cutoff")
    List<CourtListStatusEntity> findByPublishDateBefore(@Param("cutoff") LocalDate cutoff);

}
