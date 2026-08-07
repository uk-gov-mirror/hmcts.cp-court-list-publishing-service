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

import uk.gov.hmcts.cp.services.CaTHService;

/**
 * Integration tests for the SJP async publish pipeline: POST /api/court-list-publish/sjp/publishCourtList
 * queues a job (via the same task-manager {@code ExecutionService} used by the standard/online-public
 * flow) which uploads the transformed payload to blob storage (Azurite, same naming convention as the
 * standard flow — {@link CaTHService#buildBlobName}) before sending to CaTH. Tracked in
 * {@code court_list_publish_status} — the same table as the standard flow — keyed on
 * (courtListType, publishDate) with courtCentreId always null (SJP has no court-centre concept).
 *
 * <p>{@link SjpCaTHPayloadIntegrationTest} already covers the CaTH payload/header content;
 * this class covers the parts specific to this change: async completion tracked in
 * {@code court_list_publish_status}, the blob upload (unique name per {@code courtListId}), and
 * that a repeat publish for the same dedup key reuses and overwrites the same row (same as the
 * standard flow — no content-based dedup, even for identical content).
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

    /** Dates used by this class's tests — kept distinct per test so a scoped delete is exact. */
    private static final List<LocalDate> OWN_PUBLISH_DATES = List.of(
            LocalDate.of(2025, 7, 15), LocalDate.of(2025, 7, 16), LocalDate.of(2025, 7, 17));

    @BeforeEach
    void setUp() throws SQLException {
        resetWireMock();
        if (!BLOB_CONTAINER.exists()) {
            BLOB_CONTAINER.create();
        }
        deleteOwnRows();
    }

    /**
     * Deletes only the rows this class's own tests create, rather than {@link #clearTables()}'s
     * full-table truncate — court_list_publish_status is shared with the standard flow, so a
     * blanket truncate here would also wipe any standard-flow rows left behind by other IT
     * classes that happen to run earlier, making a post-suite DB inspection misleading.
     */
    private void deleteOwnRows() throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM court_list_publish_status WHERE court_centre_id IS NULL "
                             + "AND court_list_type = 'SJP_PUBLIC_FULL_ENGLISH' AND publish_date = ANY(?)")) {
            java.sql.Array dates = c.createArrayOf("date", OWN_PUBLISH_DATES.toArray());
            ps.setArray(1, dates);
            ps.executeUpdate();
        }
    }

    /**
     * The wire-level listType sent in the request. With no explicit language and no isWelsh in
     * the payload, it resolves to ENGLISH, so the fused CourtListType persisted for the dedup
     * key/row is always {@code SJP_PUBLIC_FULL_ENGLISH} — see {@link uk.gov.hmcts.cp.services.sjp.SjpStatusListTypeMapper}.
     */
    private static final String WIRE_LIST_TYPE = "SJP_PUBLIC_LIST";
    private static final String FUSED_LIST_TYPE = "SJP_PUBLIC_FULL_ENGLISH";

    @Test
    void publishSjpCourtList_uploadsPayloadToBlobBeforePublishing_andTracksStatusAsSuccessful() throws Exception {
        String courtIdNumeric = "777001";
        LocalDate publishDate = LocalDate.of(2025, 7, 15);
        String caseUrn = "URN-BLOB-001";

        postSjpRequest(sjpRequestJson(WIRE_LIST_TYPE, courtIdNumeric, "2025-07-15T09:00:00", caseUrn));

        SjpStatusRow row = waitForPublishStatus(FUSED_LIST_TYPE, publishDate, "SUCCESSFUL", SJP_TASK_TIMEOUT_MS);

        assertThat(row.courtListId).isNotNull();

        String blobName = CaTHService.buildBlobName(row.courtListId);
        assertThat(BLOB_CONTAINER.getBlobClient(blobName).exists())
                .as("payload should be uploaded to blob storage before publishing, blobName=%s", blobName)
                .isTrue();

        String blobContent = BLOB_CONTAINER.getBlobClient(blobName).downloadContent().toString();
        assertThat(blobContent).contains(caseUrn);

        assertThat(countCathRequestsContaining(caseUrn)).isEqualTo(1);
    }

    @Test
    void publishSjpCourtList_reusesSameCourtListId_forSameListTypeAndDate() throws Exception {
        String courtIdNumeric = "777002";
        LocalDate publishDate = LocalDate.of(2025, 7, 16);

        postSjpRequest(sjpRequestJson(WIRE_LIST_TYPE, courtIdNumeric, "2025-07-16T09:00:00", "URN-DEDUPKEY-001"));
        SjpStatusRow first = waitForPublishStatus(FUSED_LIST_TYPE, publishDate, "SUCCESSFUL", SJP_TASK_TIMEOUT_MS);

        // Different caseUrn (different content) for the same dedup key: a genuine republish,
        // not a dedup-skip, so waiting for SUCCESSFUL again reliably means the second job ran.
        postSjpRequest(sjpRequestJson(WIRE_LIST_TYPE, courtIdNumeric, "2025-07-16T09:00:00", "URN-DEDUPKEY-002"));
        waitForPublishStatusAfter(FUSED_LIST_TYPE, publishDate, "SUCCESSFUL", first.lastUpdated, SJP_TASK_TIMEOUT_MS);

        List<SjpStatusRow> rows = queryAllSjpStatusRows(FUSED_LIST_TYPE, publishDate);
        assertThat(rows).as("dedup key should reuse the same row, not create a new one").hasSize(1);
        assertThat(rows.getFirst().courtListId).isEqualTo(first.courtListId);
    }

    @Test
    void publishSjpCourtList_alwaysRepublishes_whenTriggeredAgainForSameRow_evenWithIdenticalContent() throws Exception {
        // Same as the standard flow: a repeat trigger for the same dedup key reuses and
        // overwrites the same row, doing the full transform/upload/publish again — no
        // content-based dedup, even when the content is byte-for-byte identical.
        String courtIdNumeric = "777003";
        LocalDate publishDate = LocalDate.of(2025, 7, 17);
        String caseUrn = "URN-NODUP-001";
        String requestJson = sjpRequestJson(WIRE_LIST_TYPE, courtIdNumeric, "2025-07-17T09:00:00", caseUrn);

        postSjpRequest(requestJson);
        SjpStatusRow first = waitForPublishStatus(FUSED_LIST_TYPE, publishDate, "SUCCESSFUL", SJP_TASK_TIMEOUT_MS);
        assertThat(countCathRequestsContaining(caseUrn)).isEqualTo(1);

        // The synchronous accept path resets publishStatus to REQUESTED before queuing, so
        // waiting for SUCCESSFUL again (with a changed lastUpdated) reliably signals the
        // second job ran and completed, rather than reading the first job's stale row.
        postSjpRequest(requestJson);
        SjpStatusRow second = waitForPublishStatusAfter(FUSED_LIST_TYPE, publishDate, "SUCCESSFUL", first.lastUpdated, SJP_TASK_TIMEOUT_MS);

        assertThat(second.courtListId).isEqualTo(first.courtListId);
        assertThat(second.publishStatus).isEqualTo("SUCCESSFUL");
        assertThat(countCathRequestsContaining(caseUrn))
                .as("a repeat trigger republishes to CaTH again, even with identical content")
                .isEqualTo(2);
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
        UUID courtListId;
        String publishStatus;
        Timestamp lastUpdated;
    }

    /** SJP rows always have courtCentreId null (no court-centre concept), so that's the shared-table discriminator. */
    private SjpStatusRow querySjpStatusRow(String listType, LocalDate publishDate) throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT court_list_id, publish_status, last_updated FROM court_list_publish_status "
                             + "WHERE court_centre_id IS NULL AND court_list_type = ? AND publish_date = ?")) {
            ps.setString(1, listType);
            ps.setObject(2, publishDate);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return toRow(rs);
            }
        }
    }

    private List<SjpStatusRow> queryAllSjpStatusRows(String listType, LocalDate publishDate) throws SQLException {
        List<SjpStatusRow> rows = new ArrayList<>();
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT court_list_id, publish_status, last_updated FROM court_list_publish_status "
                             + "WHERE court_centre_id IS NULL AND court_list_type = ? AND publish_date = ?")) {
            ps.setString(1, listType);
            ps.setObject(2, publishDate);
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
        row.courtListId = (UUID) rs.getObject("court_list_id");
        row.publishStatus = rs.getString("publish_status");
        row.lastUpdated = rs.getTimestamp("last_updated");
        return row;
    }

    private SjpStatusRow waitForPublishStatus(String listType, LocalDate publishDate,
                                              String targetStatus, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SjpStatusRow last = null;
        while (System.currentTimeMillis() < deadline) {
            last = querySjpStatusRow(listType, publishDate);
            if (last != null && targetStatus.equals(last.publishStatus)) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("court_list_publish_status did not reach " + targetStatus + " within " + timeoutMs
                + "ms. last=" + (last == null ? "no row" : last.publishStatus));
    }

    /**
     * Waits for the row to both reach {@code targetStatus} AND have a {@code lastUpdated} newer than
     * {@code previous} — plain status polling isn't enough here since the synchronous accept path
     * itself resets status to REQUESTED (with a fresh lastUpdated) before the job even runs, so a
     * naive "is it targetStatus yet" check could read a stale row from an earlier request.
     */
    private SjpStatusRow waitForPublishStatusAfter(String listType, LocalDate publishDate,
                                                    String targetStatus, Timestamp previous, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SjpStatusRow last = null;
        while (System.currentTimeMillis() < deadline) {
            last = querySjpStatusRow(listType, publishDate);
            if (last != null && targetStatus.equals(last.publishStatus)
                    && last.lastUpdated != null && !last.lastUpdated.equals(previous)) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("court_list_publish_status did not reach " + targetStatus + " (with updated lastUpdated) within "
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
