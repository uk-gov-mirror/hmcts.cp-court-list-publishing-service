package uk.gov.hmcts.cp.repositories;

import uk.gov.hmcts.cp.domain.sjp.SjpPublishStatusEntity;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SjpPublishStatusRepository extends JpaRepository<SjpPublishStatusEntity, UUID> {

    SjpPublishStatusEntity getBySjpListId(UUID sjpListId);

    Optional<SjpPublishStatusEntity> findByCourtIdNumericAndListTypeAndPublishDate(
            String courtIdNumeric, String listType, LocalDate publishDate);
}
