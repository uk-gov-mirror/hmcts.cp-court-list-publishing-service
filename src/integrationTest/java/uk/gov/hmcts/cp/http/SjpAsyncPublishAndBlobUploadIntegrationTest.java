package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import uk.gov.hmcts.cp.task.SjpPublishTask;

/**
 * Integration tests for the SJP async publish pipeline added alongside blob-storage logging:
 * POST /api/court-list-publish/sjp/publishCourtList queues a job (via the same task-manager
 * {@code ExecutionService} used by the standard/online-public flow) which uploads the transformed
 * payload to blob storage (Azurite) before sending to CaTH, tracked in {@code sjp_publish_status}
 * keyed on (courtIdNumeric, listType, publishDate).
 *
 * <p>{@link SjpCaTHPayloadIntegrationTest} already covers the CaTH payload/header content;
 * this class covers the parts specific to this change: async completion tracked in
 * {@code sjp_publish_status}, the blob upload (unique name per {@code sjpListId}), and
 * content-hash dedup (repeat requests for the same dedup key don't re-upload/re-publish
 * identical content).
 */
public class SjpAsyncPublishAndBlobUploadIntegrationTest extends CourtListIntegrationTestBase {

    private static final String SJP_PUBLISH_ENDPOINT =
            baseUrl() + "/api/court-list-publish/sjp/publishCourtList";
    private static final MediaType SJP_CONTENT_TYPE =
            MediaType.parseMediaType("application/vnd.courtlistpublishing-service.sjp.post+json");

    private static final long SJP_TASK_TIMEOUT_MS = 60_000;
    private static final long POLL_INTERVAL_MS = 300;

    /** Blob endpoint on the host (Azurite published port); see {@link CleanupJobIntegrationTest}. */
    private static final String AZURITE_ENDPOINT = "http://localhost:10000/devstoreaccount1";
    private static final String AZURITE_ACCOUNT =
            IntegrationAzuriteStorageSupport.requireNonBlank("AZURE_STORAGE_ACCOUNT_NAME", "Azurite account name");
    private static final String AZURITE_KEY =
            IntegrationAzuriteStorageSupport.requireNonBlank("AZURE_STORAGE_ACCOUNT_KEY", "Azurite account key");
    private static final String BLOB_CONTAINER_NAME =
            IntegrationAzuriteStorageSupport.requireNonBlank("AZURE_STORAGE_CONTAINER_NAME", "Blob container name");
    private static final BlobContainerClient BLOB_CONTAINER = createBlobContainer();

    private static String baseUrl() {
        return System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    }

    private static BlobContainerClient createBlobContainer() {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .endpoint(AZURITE_ENDPOINT)
                .credential(new StorageSharedKeyCredential(AZURITE_ACCOUNT, AZURITE_KEY))
                .buildClient();
        return serviceClient.getBlobContainerClient(BLOB_CONTAINER_NAME);
    }

    @BeforeEach
    void setUp() throws SQLException {
        resetWireMock();
        if (!BLOB_CONTAINER.exists()) {
            BLOB_CONTAINER.create();
        }
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("TRUNCATE TABLE sjp_publish_status RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void publishSjpCourtList_uploadsPayloadToBlobBeforePublishing_andTracksStatusAsSuccessful() throws Exception {
        String courtIdNumeric = "777001";
        String listType = "SJP_PUBLIC_LIST";
        LocalDate publishDate = LocalDate.of(2025, 7, 15);
        String caseUrn = "URN-BLOB-001";

        postSjpRequest(sjpRequestJson(listType, courtIdNumeric, "2025-07-15T09:00:00", caseUrn));

        SjpStatusRow row = waitForPublishStatus(courtIdNumeric, listType, publishDate, "SUCCESSFUL", SJP_TASK_TIMEOUT_MS);

        assertThat(row.sjpListId).isNotNull();
        assertThat(row.payloadHash).isNotBlank();

        String blobName = SjpPublishTask.buildBlobName(row.sjpListId);
        assertThat(BLOB_CONTAINER.getBlobClient(blobName).exists())
                .as("payload should be uploaded to blob storage before publishing, blobName=%s", blobName)
                .isTrue();

        String blobContent = BLOB_CONTAINER.getBlobClient(blobName).downloadContent().toString();
        assertThat(blobContent).contains(caseUrn);

        assertThat(countCathRequestsContaining(caseUrn)).isEqualTo(1);
    }

    @Test
    void publishSjpCourtList_reusesSameSjpListId_forSameCourtIdListTypeAndDate() throws Exception {
        String courtIdNumeric = "777002";
        String listType = "SJP_PUBLIC_LIST";
        LocalDate publishDate = LocalDate.of(2025, 7, 16);

        postSjpRequest(sjpRequestJson(listType, courtIdNumeric, "2025-07-16T09:00:00", "URN-DEDUPKEY-001"));
        SjpStatusRow first = waitForPublishStatus(courtIdNumeric, listType, publishDate, "SUCCESSFUL", SJP_TASK_TIMEOUT_MS);

        // Different caseUrn (different content) for the same dedup key: a genuine republish,
        // not a dedup-skip, so waiting for SUCCESSFUL again reliably means the second job ran.
        postSjpRequest(sjpRequestJson(listType, courtIdNumeric, "2025-07-16T09:00:00", "URN-DEDUPKEY-002"));
        waitForPublishStatusAfter(courtIdNumeric, listType, publishDate, "SUCCESSFUL", first.lastUpdated, SJP_TASK_TIMEOUT_MS);

        List<SjpStatusRow> rows = queryAllSjpStatusRows(courtIdNumeric, listType, publishDate);
        assertThat(rows).as("dedup key should reuse the same row, not create a new one").hasSize(1);
        assertThat(rows.get(0).sjpListId).isEqualTo(first.sjpListId);
    }

    @Test
    void publishSjpCourtList_skipsDuplicateBlobUploadAndCaTHCall_whenContentUnchanged() throws Exception {
        String courtIdNumeric = "777003";
        String listType = "SJP_PUBLIC_LIST";
        LocalDate publishDate = LocalDate.of(2025, 7, 17);
        String caseUrn = "URN-NODUP-001";
        String requestJson = sjpRequestJson(listType, courtIdNumeric, "2025-07-17T09:00:00", caseUrn);

        postSjpRequest(requestJson);
        SjpStatusRow first = waitForPublishStatus(courtIdNumeric, listType, publishDate, "SUCCESSFUL", SJP_TASK_TIMEOUT_MS);
        assertThat(countCathRequestsContaining(caseUrn)).isEqualTo(1);

        // Same content again for the same dedup key: task should skip blob upload + CaTH publish.
        // The synchronous accept path resets publishStatus to REQUESTED before queuing, so waiting
        // for SUCCESSFUL again (with a changed lastUpdated) reliably signals the second job ran
        // its dedup check (not just a false-positive read of the first job's already-SUCCESSFUL row).
        postSjpRequest(requestJson);
        SjpStatusRow second = waitForPublishStatusAfter(courtIdNumeric, listType, publishDate, "SUCCESSFUL", first.lastUpdated, SJP_TASK_TIMEOUT_MS);

        assertThat(second.sjpListId).isEqualTo(first.sjpListId);
        assertThat(second.payloadHash).isEqualTo(first.payloadHash);
        assertThat(second.publishStatus).isEqualTo("SUCCESSFUL");
        assertThat(countCathRequestsContaining(caseUrn))
                .as("identical content should not be re-published to CaTH")
                .isEqualTo(1);
    }

    // ── request helpers ──────────────────────────────────────────────────────

    private String sjpRequestJson(String listType, String courtIdNumeric, String generatedDateAndTime, String caseUrn) {
        return """
            {
              "listType": "%s",
              "listPayload": {
                "generatedDateAndTime": "%s",
                "courtIdNumeric": "%s",
                "readyCases": [
                  {
                    "caseUrn": "%s",
                    "defendantName": "Test Defendant",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence", "wording": "Details"}]
                  }
                ]
              }
            }
            """.formatted(listType, generatedDateAndTime, courtIdNumeric, caseUrn);
    }

    private ResponseEntity<String> postSjpRequest(String requestJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(SJP_CONTENT_TYPE);
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        ResponseEntity<String> response = http.exchange(
                SJP_PUBLISH_ENDPOINT, HttpMethod.POST, new HttpEntity<>(requestJson, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    // ── DB polling helpers ───────────────────────────────────────────────────

    private static final class SjpStatusRow {
        UUID sjpListId;
        String publishStatus;
        String payloadHash;
        Timestamp lastUpdated;
    }

    private SjpStatusRow querySjpStatusRow(String courtIdNumeric, String listType, LocalDate publishDate) throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sjp_list_id, publish_status, payload_hash, last_updated FROM sjp_publish_status "
                             + "WHERE court_id_numeric = ? AND list_type = ? AND publish_date = ?")) {
            ps.setString(1, courtIdNumeric);
            ps.setString(2, listType);
            ps.setObject(3, publishDate);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return toRow(rs);
            }
        }
    }

    private List<SjpStatusRow> queryAllSjpStatusRows(String courtIdNumeric, String listType, LocalDate publishDate) throws SQLException {
        List<SjpStatusRow> rows = new ArrayList<>();
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sjp_list_id, publish_status, payload_hash, last_updated FROM sjp_publish_status "
                             + "WHERE court_id_numeric = ? AND list_type = ? AND publish_date = ?")) {
            ps.setString(1, courtIdNumeric);
            ps.setString(2, listType);
            ps.setObject(3, publishDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(toRow(rs));
                }
            }
        }
        return rows;
    }

    private static SjpStatusRow toRow(ResultSet rs) throws SQLException {
        SjpStatusRow row = new SjpStatusRow();
        row.sjpListId = (UUID) rs.getObject("sjp_list_id");
        row.publishStatus = rs.getString("publish_status");
        row.payloadHash = rs.getString("payload_hash");
        row.lastUpdated = rs.getTimestamp("last_updated");
        return row;
    }

    private SjpStatusRow waitForPublishStatus(String courtIdNumeric, String listType, LocalDate publishDate,
                                              String targetStatus, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SjpStatusRow last = null;
        while (System.currentTimeMillis() < deadline) {
            last = querySjpStatusRow(courtIdNumeric, listType, publishDate);
            if (last != null && targetStatus.equals(last.publishStatus)) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("sjp_publish_status did not reach " + targetStatus + " within " + timeoutMs
                + "ms. last=" + (last == null ? "no row" : last.publishStatus));
    }

    /**
     * Waits for the row to both reach {@code targetStatus} AND have a {@code lastUpdated} newer than
     * {@code previous} — plain status polling isn't enough here since the synchronous accept path
     * itself resets status to REQUESTED (with a fresh lastUpdated) before the job even runs, so a
     * naive "is it targetStatus yet" check could read a stale row from an earlier request.
     */
    private SjpStatusRow waitForPublishStatusAfter(String courtIdNumeric, String listType, LocalDate publishDate,
                                                    String targetStatus, Timestamp previous, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SjpStatusRow last = null;
        while (System.currentTimeMillis() < deadline) {
            last = querySjpStatusRow(courtIdNumeric, listType, publishDate);
            if (last != null && targetStatus.equals(last.publishStatus)
                    && last.lastUpdated != null && !last.lastUpdated.equals(previous)) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("sjp_publish_status did not reach " + targetStatus + " (with updated lastUpdated) within "
                + timeoutMs + "ms. last=" + (last == null ? "no row" : last.publishStatus));
    }

    // ── WireMock helpers ─────────────────────────────────────────────────────

    private int countCathRequestsContaining(String marker) throws Exception {
        ResponseEntity<String> admin = http.getForEntity(WIREMOCK_ADMIN_REQUESTS, String.class);
        assertThat(admin.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = objectMapper.readTree(admin.getBody());
        JsonNode requests = root.has("requests") ? root.get("requests") : root;

        int count = 0;
        for (JsonNode entry : requests) {
            JsonNode req = entry.has("request") ? entry.get("request") : entry;
            if (!"POST".equalsIgnoreCase(req.path("method").asText())) {
                continue;
            }
            String url = req.has("url") ? req.get("url").asText("") : req.path("absoluteUrl").asText("");
            if (!url.contains(CATH_PUBLICATION_URL_PATH)) {
                continue;
            }
            String body = req.has("body") ? req.get("body").asText("") : "";
            if (body.contains(marker)) {
                count++;
            }
        }
        return count;
    }
}
