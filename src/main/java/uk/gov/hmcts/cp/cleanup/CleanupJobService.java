package uk.gov.hmcts.cp.cleanup;

import com.azure.storage.blob.BlobContainerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPublisherBlobClientService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<CourtListStatusEntity> entities = repository.findByLastUpdatedBefore(cutoff);
        log.info("Cleanup: found {} record(s) older than {} days (cutoff {})", entities.size(), retentionDays, cutoff);

        for (CourtListStatusEntity entity : entities) {
            boolean pdfOk = true;
            UUID fileId = entity.getFileId();
            if (fileId != null) {
                pdfOk = deletePdfBlob(entity, fileId);
            }
            boolean cathJsonOk = deleteCathPayloadBlob(entity);
            if (pdfOk && cathJsonOk) {
                repository.delete(entity);
                log.debug("Deleted record and blob(s) for court list {}", entity.getCourtListId());
            }
        }
    }

    /**
     * Delete PDF
     */
    private boolean deletePdfBlob(CourtListStatusEntity entity, UUID fileId) {
        String blobName = CourtListPublisherBlobClientService.buildPdfBlobName(fileId);
        try {
            return blobContainerClient.getBlobClient(blobName).deleteIfExists();
        } catch (Exception e) {
            log.warn("Failed to delete PDF blob {} for court list {}: {}", blobName, entity.getCourtListId(), e.getMessage());
            return false;
        }
    }

    /**
     * Delete CaTH JSON
     */
    private boolean deleteCathPayloadBlob(CourtListStatusEntity entity) {
        String blobName = CaTHService.buildBlobName(entity.getCourtListId());
        try {
            blobContainerClient.getBlobClient(blobName).deleteIfExists();
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete CaTH JSON blob {} for court list {}: {}", blobName, entity.getCourtListId(), e.getMessage());
            return false;
        }
    }
}
