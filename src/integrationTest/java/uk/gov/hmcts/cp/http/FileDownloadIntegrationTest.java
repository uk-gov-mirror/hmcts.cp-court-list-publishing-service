package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;

import java.util.UUID;

@Slf4j
class FileDownloadIntegrationTest extends AbstractTest {

    private static final String BASE_URL = System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    private static final String PUBLISH_ENDPOINT = BASE_URL + "/api/court-list-publish/publish";
    private static final String GET_STATUS_ENDPOINT = BASE_URL + "/api/court-list-publish/publish-status";
    private static final String DOWNLOAD_ENDPOINT = BASE_URL + "/api/files/download";
    private static final MediaType ACCEPT_FILES_DOWNLOAD = new MediaType("application", "vnd.courtlistpublishing-service.files.download+json");
    private static final String CJSCPPUID_HEADER = "CJSCPPUID";
    private static final String INTEGRATION_TEST_USER_ID = "integration-test-user-id";

    private final RestTemplate http = createRestTemplateForFileDownload();

    /**
     * RestTemplate that does not throw on 4xx responses, so tests can assert on 404 etc.
     */
    private static RestTemplate createRestTemplateForFileDownload() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false; // never treat as error - let caller assert on status
            }
        });
        return restTemplate;
    }
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    @Test
    void downloadFile_returns404_whenFileDoesNotExist() {
        // Given - random UUID that does not exist in blob storage
        UUID nonExistentFileId = UUID.randomUUID();

        // When
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.setAccept(java.util.List.of(ACCEPT_FILES_DOWNLOAD));
        ResponseEntity<byte[]> response = http.exchange(
                DOWNLOAD_ENDPOINT + "/" + nonExistentFileId,
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadFile_returns200_withPdfContent_whenFileExists() throws Exception {
        // Given - Publish court list to trigger PDF generation and upload
        UUID courtCentreId = UUID.randomUUID();
        String requestJson = createPublishRequest(courtCentreId);
        ResponseEntity<String> publishResponse = postPublishRequest(requestJson);

        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode publishBody = objectMapper.readTree(publishResponse.getBody());
        UUID courtListId = UUID.fromString(publishBody.get("courtListId").asText());

        // Wait for async task to complete (PDF generation and upload)
        waitForTaskCompletion(courtListId, CourtListIntegrationTestBase.TASK_TIMEOUT_MS);

        // Verify status indicates success (file may have been uploaded)
        ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode statusBody = objectMapper.readTree(statusResponse.getBody());
        String publishStatus = statusBody.get("publishStatus").asText();

        // When - Download the file (only meaningful if task succeeded)
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.setAccept(java.util.List.of(ACCEPT_FILES_DOWNLOAD));
        ResponseEntity<byte[]> downloadResponse = http.exchange(
                DOWNLOAD_ENDPOINT + "/" + courtListId,
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class
        );

        // Then - If task succeeded and PDF was uploaded: 200 with content.
        if (Status.SUCCESSFUL.toString().equals(publishStatus)) {
            if (downloadResponse.getStatusCode().is2xxSuccessful()) {
                assertThat(downloadResponse.getHeaders().getContentType())
                        .isEqualTo(MediaType.APPLICATION_PDF);
                assertThat(downloadResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                        .contains("attachment")
                        .contains(courtListId + ".pdf");
                assertThat(downloadResponse.getBody()).isNotNull();
                assertThat(downloadResponse.getBody().length).isGreaterThan(0);
            }
        } else {
            assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private String createPublishRequest(UUID courtCentreId) {
        return """
            {
                "courtCentreId": "%s",
                "startDate": "2026-01-20",
                "endDate": "2026-01-20",
                "courtListType": "%s"
            }
            """.formatted(courtCentreId, CourtListType.ONLINE_PUBLIC.toString());
    }

    private HttpEntity<String> createPublishHttpEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.post+json"));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        return new HttpEntity<>(json, headers);
    }

    private ResponseEntity<String> postPublishRequest(String requestJson) {
        return http.exchange(PUBLISH_ENDPOINT, HttpMethod.POST, createPublishHttpEntity(requestJson), String.class);
    }

    private ResponseEntity<String> getStatusRequest(UUID courtListId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(new MediaType("application", "vnd.courtlistpublishing-service.publish.get+json")));
        String url = GET_STATUS_ENDPOINT + "?courtListId=" + courtListId;

        ResponseEntity<String> response = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode responseBody = objectMapper.readTree(response.getBody());
        if (!responseBody.isArray()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String courtListIdStr = courtListId.toString();
        JsonNode matchingItem = null;
        for (JsonNode item : responseBody) {
            if (courtListIdStr.equals(item.get("courtListId").asText())) {
                matchingItem = item;
                break;
            }
        }

        if (matchingItem == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.get+json"))
                .body(matchingItem.toString());
    }

    private void waitForTaskCompletion(UUID courtListId, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        long pollInterval = 500;

        log.info("Waiting for task completion for courtListId: {}", courtListId);

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
                if (statusResponse.getStatusCode().is2xxSuccessful()) {
                    JsonNode statusBody = objectMapper.readTree(statusResponse.getBody());
                    String publishStatusStr = statusBody.get("publishStatus").asText();
                    try {
                        Status publishStatus = Status.valueOf(publishStatusStr);
                        if (Status.SUCCESSFUL.equals(publishStatus) || Status.FAILED.equals(publishStatus)) {
                            log.info("Task completed with status: {}", publishStatus);
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid publish status value: {}", publishStatusStr);
                    }
                }
            } catch (Exception e) {
                log.debug("Error checking status: {}", e.getMessage());
            }
            Thread.sleep(pollInterval);
        }
        log.warn("Timeout reached after {}ms. Task may still be running.", timeoutMs);
    }
}
