package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.openapi.model.CourtListType.STANDARD;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * STANDARD: CaTH publish plus asynchronous PDF generation and blob upload ({@code fileId} populated).
 * Download is served live by /api/court-list-publish/download which proxies progression's binary
 * /courtlist endpoint (independent of the generated blob).
 * Asserts the downloaded PDF bytes equal the progression stub's bytes (the binary minimal-pdf.pdf).
 * Clears {@code court_list_publish_status} and jobs before each test.
 */
class CourtListStandardPdfContentIntegrationTest extends CourtListIntegrationTestBase {

    private static final String PROGRESSION_PDF_RESOURCE = "wiremock/__files/minimal-pdf.pdf";
    private static final String SYNC_DOWNLOAD_ENDPOINT =
            System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service")
                    + "/api/court-list-publish/download";
    private static final String SYNC_DOWNLOAD_ACCEPT =
            "application/vnd.courtlistpublishing-service.download.get+json";

    @BeforeEach
    void clearTablesBeforeTest() {
        clearTables();
    }

    @Test
    void publishCourtList_shouldPublishToCaTHAndServeProgressionBinaryPdfOnSyncDownloadForSTANDARD() throws Exception {
        byte[] expectedPdfBytes = loadResourceBytes(PROGRESSION_PDF_RESOURCE);

        UUID courtListId = publishCourtListExpectingRequestedInDb(STANDARD.toString());

        waitForPDFGenerationFileCompletion(courtListId, FILE_COMPLETION_TIMEOUT_MS);
        try (Connection c = connection()) {
            assertPublishStatusRow(c, courtListId, "SUCCESSFUL", "SUCCESSFUL", null);
            assertCourtListType(c, courtListId, STANDARD.toString());
            assertFileIdIsNotNull(c, courtListId);
        }

        byte[] downloaded = downloadStandardCourtListSync();
        assertThat(downloaded).isNotNull().isEqualTo(expectedPdfBytes);
    }

    private byte[] downloadStandardCourtListSync() {
        String url = SYNC_DOWNLOAD_ENDPOINT
                + "?courtCentreId=f8254db1-1683-483e-afb3-b87fde5a0a26"
                + "&startDate=2026-01-20&endDate=2026-01-20"
                + "&courtListType=" + STANDARD.name();
        HttpHeaders headers = new HttpHeaders();
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType(SYNC_DOWNLOAD_ACCEPT)));
        ResponseEntity<byte[]> response = http.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        return response.getBody();
    }

    private void assertFileIdIsNotNull(Connection c, UUID courtListId) throws Exception {
        try (var ps = c.prepareStatement("SELECT file_id FROM court_list_publish_status WHERE court_list_id = ?")) {
            ps.setObject(1, courtListId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("file_id")).isNotNull();
            }
        }
    }

    private byte[] loadResourceBytes(String resourcePath) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as("resource %s must exist", resourcePath).isNotNull();
            return in.readAllBytes();
        }
    }
}
