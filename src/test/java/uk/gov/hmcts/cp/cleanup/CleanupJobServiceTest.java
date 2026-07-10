package uk.gov.hmcts.cp.cleanup;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPublisherBlobClientService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanupJobServiceTest {

    private static final int RETENTION_DAYS = 30;

    @Mock
    private CourtListStatusRepository repository;

    @Mock
    private BlobContainerClient blobContainerClient;

    private CleanupJobService cleanupJobService;

    private CourtListStatusEntity buildEntity(UUID courtListId, UUID fileId) {
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                UUID.randomUUID(),
                Status.SUCCESSFUL,
                Status.SUCCESSFUL,
                CourtListType.STANDARD,
                Instant.now().minus(java.time.Duration.ofDays(RETENTION_DAYS + 10)));
        entity.setPublishDate(LocalDate.now().minusDays(RETENTION_DAYS + 10));
        entity.setFileId(fileId);
        return entity;
    }

    private void mockBlob(String blobName, boolean existedAndDeleted) {
        BlobClient blobClient = mock(BlobClient.class);
        when(blobContainerClient.getBlobClient(blobName)).thenReturn(blobClient);
        when(blobClient.deleteIfExists()).thenReturn(existedAndDeleted);
    }

    private void mockBlobThrows(String blobName) {
        BlobClient blobClient = mock(BlobClient.class);
        when(blobContainerClient.getBlobClient(blobName)).thenReturn(blobClient);
        when(blobClient.deleteIfExists()).thenThrow(new RuntimeException("blob storage error"));
    }

    @Test
    void cleanupOldData_shouldNotTouchBlobsOrDb_whenNoRecordsFound() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of());

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, never()).delete(any());
        verify(blobContainerClient, never()).getBlobClient(any());
    }

    @Test
    void cleanupOldData_shouldDeleteRecord_whenBothPdfAndJsonBlobsExistAndAreDeleted() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        UUID courtListId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CourtListStatusEntity entity = buildEntity(courtListId, fileId);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of(entity));

        mockBlob(CourtListPublisherBlobClientService.buildPdfBlobName(fileId), true);
        mockBlob(CaTHService.buildBlobName(courtListId), true);

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, times(1)).delete(entity);
    }

    @Test
    void cleanupOldData_shouldKeepRecord_whenPdfBlobDeletionThrows() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        UUID courtListId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CourtListStatusEntity entity = buildEntity(courtListId, fileId);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of(entity));

        mockBlobThrows(CourtListPublisherBlobClientService.buildPdfBlobName(fileId));
        mockBlob(CaTHService.buildBlobName(courtListId), true);

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, never()).delete(any());
    }

    @Test
    void cleanupOldData_shouldKeepRecord_whenJsonBlobDeletionThrows() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        UUID courtListId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CourtListStatusEntity entity = buildEntity(courtListId, fileId);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of(entity));

        mockBlob(CourtListPublisherBlobClientService.buildPdfBlobName(fileId), true);
        mockBlobThrows(CaTHService.buildBlobName(courtListId));

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, never()).delete(any());
    }

    @Test
    void cleanupOldData_shouldDeleteRecord_whenOnlyPdfBlobExists() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        UUID courtListId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CourtListStatusEntity entity = buildEntity(courtListId, fileId);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of(entity));

        // PDF blob exists and is deleted; JSON blob does not exist (deleteIfExists returns false, no exception)
        mockBlob(CourtListPublisherBlobClientService.buildPdfBlobName(fileId), true);
        mockBlob(CaTHService.buildBlobName(courtListId), false);

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, times(1)).delete(entity);
    }

    @Test
    void cleanupOldData_shouldDeleteRecord_whenOnlyJsonBlobExists() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        UUID courtListId = UUID.randomUUID();
        // No fileId recorded -> no PDF blob to attempt
        CourtListStatusEntity entity = buildEntity(courtListId, null);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of(entity));

        mockBlob(CaTHService.buildBlobName(courtListId), true);

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, times(1)).delete(entity);
    }

    @Test
    void cleanupOldData_shouldDeleteRecord_whenNeitherPdfNorJsonBlobExists() {
        cleanupJobService = new CleanupJobService(repository, blobContainerClient);
        UUID courtListId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CourtListStatusEntity entity = buildEntity(courtListId, fileId);
        when(repository.findByPublishDateBefore(any())).thenReturn(List.of(entity));

        // Neither blob exists; deleteIfExists returns false for both, no exceptions
        mockBlob(CourtListPublisherBlobClientService.buildPdfBlobName(fileId), false);
        mockBlob(CaTHService.buildBlobName(courtListId), false);

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, times(1)).delete(entity);
    }

    @Test
    void cleanupOldData_shouldSkip_whenBlobContainerClientIsNull() {
        cleanupJobService = new CleanupJobService(repository, null);

        cleanupJobService.cleanupOldData(RETENTION_DAYS);

        verify(repository, never()).findByPublishDateBefore(any());
        verify(repository, never()).delete(any());
    }
}
