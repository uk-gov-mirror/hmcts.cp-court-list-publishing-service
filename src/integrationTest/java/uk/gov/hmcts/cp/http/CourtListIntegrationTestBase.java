package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.openapi.model.Status;

/**
 * Base for court list integration tests (STANDARD, ONLINE_PUBLIC, task tests). Shares endpoints, HTTP helpers,
 * publish/wait/download flow, and DB helpers (clearTables, connection, assertJobsTableHasRowForCourtListId,
 * assertPublishStatusRow, loadExpectedText) so concrete tests only provide their assertion (e.g. byte or text comparison).
 */
abstract class CourtListIntegrationTestBase extends AbstractTest {

    protected static final Logger log = LoggerFactory.getLogger(CourtListIntegrationTestBase.class);

    protected static final String PUBLISH_ENDPOINT = baseUrl() + "/api/court-list-publish/publish";
    protected static final String GET_STATUS_ENDPOINT = baseUrl() + "/api/court-list-publish/publish-status";
    protected static final String DOWNLOAD_ENDPOINT = baseUrl() + "/api/files/download";
    protected static final String CJSCPPUID_HEADER = "CJSCPPUID";
    protected static final String INTEGRATION_TEST_USER_ID = "integration-test-user-id";
    protected static final MediaType ACCEPT_FILES_DOWNLOAD =
            new MediaType("application", "vnd.courtlistpublishing-service.files.download+json");

    /** Shared integration timeouts (async task, WireMock polling, CaTH body capture). */
    public static final long TASK_TIMEOUT_MS = 120_000;
    public static final long WIREMOCK_POLL_MS = 250;
    public static final long CATH_BODY_WAIT_MS = 20_000;

    protected static final long FILE_COMPLETION_TIMEOUT_MS = TASK_TIMEOUT_MS;

    protected static final String JDBC_URL = System.getProperty("CP_CLP_INTEGRATION_JDBC_URL", "jdbc:postgresql://localhost:55432/courtlistpublishing");
    protected static final String JDBC_USER = System.getProperty("CP_CLP_DATASOURCE_USERNAME", "courtlistpublishing");
    protected static final String JDBC_PASSWORD = System.getProperty("CP_CLP_DATASOURCE_PASSWORD", "courtlistpublishing");

    protected final RestTemplate http = new RestTemplate();
    protected final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    private static String baseUrl() {
        return System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    }

    protected String createPublishRequestJson(UUID courtCentreId, String courtListType) {
        return """
            {
                "courtCentreId": "%s",
                "startDate": "2026-01-20",
                "endDate": "2026-01-20",
                "courtListType": "%s"
            }
            """.formatted(courtCentreId, courtListType);
    }

    protected HttpEntity<String> createPublishHttpEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.post+json"));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        return new HttpEntity<>(json, headers);
    }

    protected ResponseEntity<String> postPublishRequest(String requestJson) {
        return http.exchange(PUBLISH_ENDPOINT, HttpMethod.POST, createPublishHttpEntity(requestJson), String.class);
    }

    protected ResponseEntity<String> getStatusRequest(UUID courtListId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(new MediaType("application", "vnd.courtlistpublishing-service.publish.get+json")));
        ResponseEntity<String> response = http.exchange(
                GET_STATUS_ENDPOINT + "?courtListId=" + courtListId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        JsonNode responseBody = parseResponse(response);
        if (!responseBody.isArray()) {
            return ResponseEntity.status(404).build();
        }

        String idStr = courtListId.toString();
        for (JsonNode item : responseBody) {
            if (idStr.equals(item.get("courtListId").asText())) {
                return ResponseEntity.ok()
                        .contentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.get+json"))
                        .body(item.toString());
            }
        }
        return ResponseEntity.status(404).build();
    }

    protected JsonNode parseResponse(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected byte[] downloadPdf(UUID courtListId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(ACCEPT_FILES_DOWNLOAD));
        ResponseEntity<byte[]> response = http.exchange(
                DOWNLOAD_ENDPOINT + "/" + courtListId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
    }

    protected void waitForPDFGenerationFileCompletion(UUID courtListId, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        long pollInterval = 500;
        int pollCount = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
                if (statusResponse.getStatusCode().is2xxSuccessful()) {
                    JsonNode statusBody = parseResponse(statusResponse);
                    String fileStatusStr = statusBody.get("fileStatus").asText();
                    try {
                        Status s = Status.valueOf(fileStatusStr);
                        if (s == Status.SUCCESSFUL || s == Status.FAILED) {
                            return;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // invalid enum, keep polling
                    }
                }
            } catch (Exception e) {
                if (pollCount % 10 == 0) {
                    log.warn("Poll #{}: {}", pollCount, e.getMessage());
                }
            }
            pollCount++;
            Thread.sleep(pollInterval);
        }

        String finalStatusMsg = "timeout";
        try {
            ResponseEntity<String> finalStatus = getStatusRequest(courtListId);
            if (finalStatus.getStatusCode().is2xxSuccessful()) {
                JsonNode statusBody = parseResponse(finalStatus);
                finalStatusMsg = "publishStatus=" + statusBody.get("publishStatus").asText()
                        + ", fileStatus=" + (statusBody.has("fileStatus") ? statusBody.get("fileStatus").asText() : "n/a");
                log.error("Final status: {}", finalStatusMsg);
            }
        } catch (Exception e) {
            log.error("Could not get final status: {}", e.getMessage(), e);
        }
        throw new AssertionError("File did not complete within " + timeoutMs + "ms. " + finalStatusMsg);
    }

    /**
     * Waits for task completion by polling the status endpoint until publishStatus is SUCCESSFUL or FAILED.
     */
    protected void waitForTaskCompletion(UUID courtListId, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        long pollInterval = 500;
        int pollCount = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
                if (statusResponse.getStatusCode().is2xxSuccessful()) {
                    JsonNode statusBody = parseResponse(statusResponse);
                    String publishStatusStr = statusBody.get("publishStatus").asText();
                    try {
                        Status publishStatus = Status.valueOf(publishStatusStr);
                        if (Status.SUCCESSFUL.equals(publishStatus) || Status.FAILED.equals(publishStatus)) {
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid publish status value: {}", publishStatusStr);
                    }
                }
            } catch (Exception e) {
                if (pollCount % 10 == 0) {
                    log.warn("Poll #{}: Error checking status - {}", pollCount, e.getMessage());
                }
            }
            pollCount++;
            Thread.sleep(pollInterval);
        }

        String finalStatusMsg = "timeout";
        try {
            ResponseEntity<String> finalStatus = getStatusRequest(courtListId);
            if (finalStatus.getStatusCode().is2xxSuccessful()) {
                JsonNode statusBody = parseResponse(finalStatus);
                finalStatusMsg = "publishStatus=" + statusBody.get("publishStatus").asText()
                        + ", fileStatus=" + (statusBody.has("fileStatus") ? statusBody.get("fileStatus").asText() : "n/a");
                log.error("Final status: {}", finalStatusMsg);
            }
        } catch (Exception e) {
            log.error("Could not get final status: {}", e.getMessage(), e);
        }
        throw new AssertionError("Task did not complete within " + timeoutMs + "ms. " + finalStatusMsg);
    }

    /**
     * Truncates court_list_publish_status and jobs. Call from @BeforeEach in tests that need a clean DB.
     */
    protected void clearTables() {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("TRUNCATE TABLE court_list_publish_status RESTART IDENTITY CASCADE");
            truncateJobsIfExists(s);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear tables: " + e.getMessage(), e);
        }
    }

    private void truncateJobsIfExists(Statement s) throws SQLException {
        try {
            s.executeUpdate("TRUNCATE TABLE jobs RESTART IDENTITY CASCADE");
        } catch (SQLException e) {
            if (e.getSQLState() == null || !e.getSQLState().startsWith("42")) {
                throw e;
            }
        }
    }

    protected Connection connection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    protected void assertJobsTableHasRowForCourtListId(Connection c, UUID courtListId) throws SQLException, InterruptedException {
        String id = courtListId.toString();
        final int maxAttempts = 5;
        final int pollIntervalMs = 150;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (jobsTableContains(c, id)) {
                return;
            }
            if (attempt < maxAttempts - 1) {
                Thread.sleep(pollIntervalMs);
            }
        }
        try (var ps = c.prepareStatement("SELECT publish_status, file_status FROM court_list_publish_status WHERE court_list_id = ?")) {
            ps.setObject(1, courtListId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && "SUCCESSFUL".equals(rs.getString("publish_status")) && "SUCCESSFUL".equals(rs.getString("file_status"))) {
                    return;
                }
            }
        }
        assertThat(false).as("Jobs table has no row for court_list_id %s", courtListId).isTrue();
    }

    private boolean jobsTableContains(Connection c, String courtListIdStr) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM jobs LIMIT 100")) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    if (v != null && String.valueOf(v).contains(courtListIdStr)) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            if (e.getSQLState() == null || !e.getSQLState().startsWith("42")) {
                throw e;
            }
        }
        return false;
    }

    protected void assertPublishStatusRow(Connection c, UUID courtListId, String publishStatus, String fileStatus, UUID fileId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT publish_status, file_status, file_id FROM court_list_publish_status WHERE court_list_id = ?")) {
            ps.setObject(1, courtListId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("publish_status")).isEqualTo(publishStatus);
                assertThat(rs.getString("file_status")).isEqualTo(fileStatus);
                if (fileId != null) {
                    assertThat(rs.getObject("file_id")).isNotNull();
                    assertThat(rs.getObject("file_id").toString()).isEqualTo(fileId.toString());
                }
                assertThat(rs.next()).isFalse();
            }
        }
    }

    /**
     * Asserts that the court_list_publish_status row for the given court list has the expected court_list_type.
     */
    protected void assertCourtListType(Connection c, UUID courtListId, String expectedCourtListType) throws SQLException {
        try (var ps = c.prepareStatement("SELECT court_list_type FROM court_list_publish_status WHERE court_list_id = ?")) {
            ps.setObject(1, courtListId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("court_list_type")).isEqualTo(expectedCourtListType);
                assertThat(rs.next()).isFalse();
            }
        }
    }

    /**
     * Loads expected PDF content from a classpath resource (e.g. wiremock/__files/expected-*-pdf-content.txt).
     */
    protected String loadExpectedText(String resourcePath) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Publishes a court list; asserts 200, REQUESTED, and DB jobs row + publish row + {@code court_list_type}.
     *
     * @param courtListType same value for request JSON and DB (e.g. {@code ONLINE_PUBLIC.toString()})
     */
    protected UUID publishCourtListExpectingRequestedInDb(String courtListType) throws Exception {
        UUID courtCentreId = UUID.randomUUID();
        ResponseEntity<String> publishResponse =
                postPublishRequest(createPublishRequestJson(courtCentreId, courtListType));
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parseResponse(publishResponse);
        UUID courtListId = UUID.fromString(body.get("courtListId").asText());
        assertThat(body.get("publishStatus").asText()).isEqualTo("REQUESTED");
        try (Connection c = connection()) {
            assertJobsTableHasRowForCourtListId(c, courtListId);
            assertPublishStatusRow(c, courtListId, "REQUESTED", "REQUESTED", null);
            assertCourtListType(c, courtListId, courtListType);
        }
        return courtListId;
    }

    /** After PDF generation completes: HTTP + DB assert SUCCESSFUL and file id matches {@code courtListId}. */
    protected void awaitSuccessfulPdfAndAssertDb(UUID courtListId, String courtListTypeForDb) throws Exception {
        waitForPDFGenerationFileCompletion(courtListId, FILE_COMPLETION_TIMEOUT_MS);
        ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode status = parseResponse(statusResponse);
        assertThat(status.get("publishStatus").asText()).isEqualTo("SUCCESSFUL");
        assertThat(status.get("fileStatus").asText()).isEqualTo("SUCCESSFUL");
        assertThat(status.get("fileId").asText()).isEqualTo(courtListId.toString());
        try (Connection c = connection()) {
            assertPublishStatusRow(c, courtListId, "SUCCESSFUL", "SUCCESSFUL", courtListId);
            assertCourtListType(c, courtListId, courtListTypeForDb);
        }
    }

    protected void assertDownloadedPdfMatchesExpectedText(UUID courtListId, String expectedTextContent) {
        byte[] downloaded = downloadPdf(courtListId);
        assertThat(downloaded).isNotNull().isNotEmpty();
        assertThat(downloaded).isEqualTo(expectedTextContent.getBytes(StandardCharsets.UTF_8));
    }
}
