package uk.gov.hmcts.cp.cleanup;

import com.azure.storage.blob.BlobContainerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPublisherBlobClientService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class CleanupJobService {

    private final CourtListStatusRepository repository;
    private final BlobContainerClient blobContainerClient;

    public CleanupJobService(CourtListStatusRepository repository,
                             @Autowired(required = true) BlobContainerClient blobContainerClient) {
        this.repository = repository;
        this.blobContainerClient = blobContainerClient;
    }

    public void cleanupOldData(int retentionDays) {
        if (blobContainerClient == null) {
            log.warn("Cleanup skipped: BlobContainerClient not available");
            return;
        }
        log.info("Cleanup started: retentionDays={}", retentionDays);
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        List<CourtListStatusEntity> entities = repository.findByPublishDateBefore(cutoff);

        if (entities.isEmpty()) {
            log.info("Cleanup: no records found to delete (cutoff {})", cutoff);
            return;
        }
        System.out.printf("Cleanup: found %d record(s) older than %d days ", entities.size(), retentionDays);
        log.info("Cleanup: found {} record(s) older than {} days (cutoff {})", entities.size(), retentionDays, cutoff);

        entities.forEach(this::cleanupEntity);
    }

    private void cleanupEntity(CourtListStatusEntity entity) {
        UUID fileId = entity.getFileId();
        boolean pdfDeleted = fileId == null
                || tryDeleteBlob(CourtListPublisherBlobClientService.buildPdfBlobName(fileId), entity.getCourtListId());
        boolean jsonDeleted = tryDeleteBlob(CaTHService.buildBlobName(entity.getCourtListId()), entity.getCourtListId());

        if (pdfDeleted && jsonDeleted) {
            repository.delete(entity);
            log.debug("Deleted record and blob(s) for court list {}", entity.getCourtListId());
        } else {
            log.warn("Cleanup: blob deletion failed for court list {} (pdfDeleted={}, jsonDeleted={}); DB record not deleted",
                    entity.getCourtListId(), pdfDeleted, jsonDeleted);
        }
    }

    /**
     * A blob that doesn't exist is not a failure (deleteIfExists() just no-ops); only an actual
     * storage error counts as a failed deletion, which keeps the DB record around for a retry.
     */
    private boolean tryDeleteBlob(String blobName, UUID courtListId) {
        try {
            blobContainerClient.getBlobClient(blobName).deleteIfExists();
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete blob {} for court list {}: {}", blobName, courtListId, e.getMessage());
            return false;
        }
    }
}
