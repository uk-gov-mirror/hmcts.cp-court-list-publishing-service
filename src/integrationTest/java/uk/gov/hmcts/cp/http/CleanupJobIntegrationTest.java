package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPublisherBlobClientService;

/** Cleanup via GET {@code /api/court-list-publish/publish-status-cleanup}; same stack as other HTTP integration tests. */
public class CleanupJobIntegrationTest extends CourtListIntegrationTestBase {

    private static final String BASE_URL = System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    private static final String CLEANUP_ENDPOINT = BASE_URL + "/api/court-list-publish/publish-status-cleanup";
    private static final MediaType CLEANUP_ACCEPT =
            MediaType.parseMediaType("application/vnd.courtlistpublishing-service.publish-status-cleanup.get+json");

    private static final int DAYS_BEYOND_RETENTION = 10;

    /**
     * Blob endpoint on the host (Azurite published port). Differs from in-container {@code AZURE_STORAGE_BLOB_ENDPOINT} in docker-compose.
     */
    private static final String AZURITE_ENDPOINT = "http://localhost:10000/devstoreaccount1";

    /** Account / key / container from {@code docker/integration-azurite-storage.env} (same as Compose {@code env_file}), or OS env override. */
    private static final String AZURITE_ACCOUNT =
            IntegrationAzuriteStorageSupport.requireNonBlank("AZURE_STORAGE_ACCOUNT_NAME", "Azurite account name");
    private static final String AZURITE_KEY =
            IntegrationAzuriteStorageSupport.requireNonBlank("AZURE_STORAGE_ACCOUNT_KEY", "Azurite account key");
    private static final String BLOB_CONTAINER_NAME =
            IntegrationAzuriteStorageSupport.requireNonBlank("AZURE_STORAGE_CONTAINER_NAME", "Blob container name");

    private static final BlobContainerClient BLOB_CONTAINER = createBlobContainer();

    private final RestTemplate client = new RestTemplate();

    private static BlobContainerClient createBlobContainer() {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .endpoint(AZURITE_ENDPOINT)
                .credential(new StorageSharedKeyCredential(AZURITE_ACCOUNT, AZURITE_KEY))
                .buildClient();
        return serviceClient.getBlobContainerClient(BLOB_CONTAINER_NAME);
    }

    @BeforeEach
    void setUp() throws SQLException {
        clearTables();
        if (!BLOB_CONTAINER.exists()) {
            BLOB_CONTAINER.create();
        }
    }

    @Test
    void cleanupOldData_shouldDeleteRecordsAndBlobs_afterRetentionPeriod() throws Exception {
        UUID courtListId1 = UUID.randomUUID();
        UUID courtListId2 = UUID.randomUUID();
        Instant oldInstant = Instant.now().minus(DAYS_BEYOND_RETENTION, ChronoUnit.DAYS);

        insertPublishStatusRow(courtListId1, oldInstant, courtListId1);
        insertPublishStatusRow(courtListId2, oldInstant, courtListId2);
        uploadPdfAndCathJson(courtListId1);
        uploadPdfAndCathJson(courtListId2);

        assertRowExists(courtListId1);
        assertRowExists(courtListId2);
        assertBlobs(courtListId1);
        assertBlobs(courtListId2);

        invokePublishStatusCleanup();

        assertRowAbsent(courtListId1);
        assertRowAbsent(courtListId2);
        assertBlobsDeleted(courtListId1);
        assertBlobsDeleted(courtListId2);
    }

    @Test
    void cleanupOldData_shouldNotDeleteRecentRecords_orTheirBlobs() throws Exception {
        UUID oldCourtListId = UUID.randomUUID();
        UUID recentCourtListId = UUID.randomUUID();

        insertPublishStatusRow(oldCourtListId, Instant.now().minus(DAYS_BEYOND_RETENTION, ChronoUnit.DAYS), oldCourtListId);
        insertPublishStatusRow(recentCourtListId, Instant.now(), recentCourtListId);
        uploadPdfAndCathJson(oldCourtListId);
        uploadPdfAndCathJson(recentCourtListId);

        invokePublishStatusCleanup();

        assertRowAbsent(oldCourtListId);
        assertBlobsDeleted(oldCourtListId);
        assertRowExists(recentCourtListId);
        assertBlobs(recentCourtListId);
    }

    @Test
    void cleanupOldData_shouldNotDeleteRecord_whenBlobDoesNotExist() throws Exception {
        UUID courtListId = UUID.randomUUID();
        insertPublishStatusRow(courtListId, Instant.now().minus(DAYS_BEYOND_RETENTION, ChronoUnit.DAYS), courtListId);

        invokePublishStatusCleanup();

        assertRowExists(courtListId);
    }

    @Test
    void cleanupOldData_shouldDeleteRecord_whenOnlyPdfExists_cathJsonNotInStorage() throws Exception {
        UUID courtListId = UUID.randomUUID();
        insertPublishStatusRow(courtListId, Instant.now().minus(DAYS_BEYOND_RETENTION, ChronoUnit.DAYS), courtListId);
        byte[] pdfContent = ("pdf-only-" + courtListId).getBytes(StandardCharsets.UTF_8);
        BLOB_CONTAINER.getBlobClient(CourtListPublisherBlobClientService.buildPdfBlobName(courtListId)).upload(
                new ByteArrayInputStream(pdfContent), pdfContent.length, true);

        invokePublishStatusCleanup();

        assertRowAbsent(courtListId);
        assertThat(BLOB_CONTAINER.getBlobClient(CourtListPublisherBlobClientService.buildPdfBlobName(courtListId)).exists()).isFalse();
        assertThat(BLOB_CONTAINER.getBlobClient(CaTHService.buildBlobName(courtListId)).exists()).isFalse();
    }

    private void invokePublishStatusCleanup() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(CLEANUP_ACCEPT));
        ResponseEntity<String> response =
                client.exchange(CLEANUP_ENDPOINT, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void insertPublishStatusRow(UUID courtListId, Instant lastUpdated, UUID fileId) throws SQLException {
        UUID courtCentreId = UUID.randomUUID();
        LocalDate publishDate = LocalDate.now();
        try (Connection c = connection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO court_list_publish_status (court_list_id, court_centre_id, publish_status, file_status, court_list_type, last_updated, publish_date, file_id) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, courtListId);
            ps.setObject(2, courtCentreId);
            ps.setString(3, "SUCCESSFUL");
            ps.setString(4, "SUCCESSFUL");
            ps.setString(5, "STANDARD");
            ps.setTimestamp(6, Timestamp.from(lastUpdated));
            ps.setDate(7, Date.valueOf(publishDate));
            if (fileId != null) {
                ps.setObject(8, fileId);
            } else {
                ps.setNull(8, Types.OTHER);
            }
            ps.executeUpdate();
        }
    }

    private void assertRowExists(UUID courtListId) throws SQLException {
        try (Connection c = connection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM court_list_publish_status WHERE court_list_id = ?")) {
            ps.setObject(1, courtListId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    private void assertRowAbsent(UUID courtListId) throws SQLException {
        try (Connection c = connection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM court_list_publish_status WHERE court_list_id = ?")) {
            ps.setObject(1, courtListId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    private void assertBlobs(UUID courtListId) {
        assertThat(BLOB_CONTAINER.getBlobClient(CourtListPublisherBlobClientService.buildPdfBlobName(courtListId)).exists()).isTrue();
        assertThat(BLOB_CONTAINER.getBlobClient(CaTHService.buildBlobName(courtListId)).exists()).isTrue();
    }

    private void assertBlobsDeleted(UUID courtListId) {
        assertThat(BLOB_CONTAINER.getBlobClient(CourtListPublisherBlobClientService.buildPdfBlobName(courtListId)).exists()).isFalse();
        assertThat(BLOB_CONTAINER.getBlobClient(CaTHService.buildBlobName(courtListId)).exists()).isFalse();
    }

    private void uploadPdfAndCathJson(UUID courtListId) {
        byte[] pdfContent = ("pdf-content-" + courtListId).getBytes(StandardCharsets.UTF_8);
        BLOB_CONTAINER.getBlobClient(CourtListPublisherBlobClientService.buildPdfBlobName(courtListId)).upload(
                new ByteArrayInputStream(pdfContent), pdfContent.length, true);
        byte[] jsonContent = ("{\"courtListId\":\"" + courtListId + "\"}").getBytes(StandardCharsets.UTF_8);
        BLOB_CONTAINER.getBlobClient(CaTHService.buildBlobName(courtListId)).upload(
                new ByteArrayInputStream(jsonContent), jsonContent.length, true);
    }
}
