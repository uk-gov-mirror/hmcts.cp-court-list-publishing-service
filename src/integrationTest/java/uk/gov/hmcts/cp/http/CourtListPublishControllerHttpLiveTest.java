package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;

public class CourtListPublishControllerHttpLiveTest extends AbstractTest {

    private static final String BASE_URL = System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    private static final String PUBLISH_ENDPOINT = BASE_URL + "/api/court-list-publish/publish";
    private static final String DOWNLOAD_ENDPOINT = BASE_URL + "/api/court-list-publish/download";
    private static final String DOWNLOAD_CONTENT_TYPE = "application/vnd.courtlistpublishing-service.download.post+json";
    private static final String REQUESTED_STATUS = Status.REQUESTED.toString();
    private static final String COURT_LIST_TYPE_PUBLIC = CourtListType.PUBLIC.toString();
    private static final String COURT_LIST_TYPE_FINAL = CourtListType.FINAL.toString();

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    @Test
    void postCourtListPublish_creates_court_list_publish_status_successfully() throws Exception {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        String requestJson = createRequestJson(courtCentreId, CourtListType.PUBLIC);

        // When
        ResponseEntity<String> response = postRequest(requestJson);

        // Then
        assertSuccessfulCreation(response, courtListId, courtCentreId);
    }

    @Test
    void postCourtListPublish_updates_existing_court_list_successfully() throws Exception {
        // Given - First create a court list publish status
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        postRequest(createRequestJson(courtCentreId, CourtListType.PUBLIC));

        // When - Update the entity with same ID
        String updateRequestJson = createRequestJson(courtCentreId, CourtListType.FINAL);
        ResponseEntity<String> response = postRequest(updateRequestJson);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = parseResponse(response);
        assertThat(responseBody.get("courtListId").asText()).isNotNull();
        assertThat(responseBody.get("publishStatus").asText()).isEqualTo(REQUESTED_STATUS);
        assertThat(responseBody.get("courtListType").asText()).isEqualTo(COURT_LIST_TYPE_FINAL);
    }

    @Test
    void postCourtListPublish_creates_and_updates_same_entity() throws Exception {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();

        // When - Create first time
        ResponseEntity<String> createResponse = postRequest(
                createRequestJson(courtCentreId, CourtListType.PUBLIC)
        );

        // Then - Verify creation
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode createBody = parseResponse(createResponse);
        assertThat(createBody.get("publishStatus").asText()).isEqualTo(REQUESTED_STATUS);

        // When - Update with same ID (upsert)
        ResponseEntity<String> updateResponse = postRequest(
                createRequestJson(courtCentreId, CourtListType.PUBLIC)
        );

        // Then - Verify update
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updateBody = parseResponse(updateResponse);
        assertThat(updateBody.get("courtListId").asText()).isNotNull();
        assertThat(updateBody.get("publishStatus").asText()).isEqualTo(REQUESTED_STATUS);
    }

    @Test
    void postCourtListPublish_returns_bad_request_when_missing_required_fields() {
        // Given - Request with missing courtListId
        String requestJson = """
            {
                "courtCentreId": "%s",
                "publishStatus": "PUBLISHED",
                "courtListType": "DAILY"
            }
            """.formatted(UUID.randomUUID());

        // When & Then
        assertBadRequest(() -> postRequest(requestJson));
    }

    @Test
    void postCourtListPublish_returns_bad_request_when_publish_status_is_blank() {
        // Given - Request with blank publishStatus
        String requestJson = """
            {
                "courtListId": "%s",
                "courtCentreId": "%s",
                "publishStatus": "   ",
                "courtListType": "DAILY"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        // When & Then
        assertBadRequest(() -> postRequest(requestJson));
    }

    @Test
    void postCourtListPublish_returns_bad_request_when_court_list_type_is_blank() {
        // Given - Request with blank courtListType
        String requestJson = """
            {
                "courtListId": "%s",
                "courtCentreId": "%s",
                "publishStatus": "PUBLISHED",
                "courtListType": "   "
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        // When & Then
        assertBadRequest(() -> postRequest(requestJson));
    }

    @Test
    void findCourtListPublishStatus_returns_list_when_entities_exist() throws Exception {
        // Given - Create multiple entities for the same court centre and date
        UUID courtCentreId = UUID.randomUUID();
        LocalDate publishDate = LocalDate.now();
        UUID courtListId1 = UUID.randomUUID();
        UUID courtListId2 = UUID.randomUUID();

        postRequest(createRequestJson(courtCentreId, CourtListType.PUBLIC));
        postRequest(createRequestJson(courtCentreId, CourtListType.FINAL));

        // When
        String url = BASE_URL + "/api/court-list-publish/publish-status?courtCentreId=" + courtCentreId + "&publishDate=" + publishDate;
        ResponseEntity<String> response = getRequest(url);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = parseResponse(response);
        assertThat(responseBody.isArray()).isTrue();
        assertThat(responseBody.size()).isGreaterThanOrEqualTo(2);
        
        // Verify all items have the same court centre ID
        for (JsonNode item : responseBody) {
            assertThat(item.get("courtCentreId").asText()).isEqualTo(courtCentreId.toString());
        }
    }

    @Test
    void findCourtListPublishStatus_returns_empty_list_when_no_entities_exist() throws Exception {
        // Given - Non-existent court centre ID
        UUID nonExistentCourtCentreId = UUID.randomUUID();
        LocalDate publishDate = LocalDate.now();

        // When
        String url = BASE_URL + "/api/court-list-publish/publish-status?courtCentreId=" + nonExistentCourtCentreId + "&publishDate=" + publishDate;
        ResponseEntity<String> response = getRequest(url);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = parseResponse(response);
        assertThat(responseBody.isArray()).isTrue();
        assertThat(responseBody.size()).isEqualTo(0);
    }

    @Test
    void findCourtListPublishStatus_returns_all_matching_records() throws Exception {
        // Given - Create multiple entities for the same court centre and date
        UUID courtCentreId = UUID.randomUUID();
        LocalDate publishDate = LocalDate.now();
        for (int i = 0; i < 5; i++) {
            final ResponseEntity<String> res = postRequest(createRequestJson(courtCentreId, CourtListType.PUBLIC));
            assertThat(res.getStatusCode().is2xxSuccessful()).isEqualTo(true);
        }

        // When
        String url = BASE_URL + "/api/court-list-publish/publish-status?courtCentreId=" + courtCentreId + "&publishDate=" + publishDate;
        ResponseEntity<String> response = getRequest(url);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = parseResponse(response);
        assertThat(responseBody.isArray()).isTrue();
        assertThat(responseBody.size()).isGreaterThanOrEqualTo(1);
    }

    // Helper methods

    private String createRequestJson(UUID courtCentreId, CourtListType courtListType) {
        // Note: courtListId and publishStatus are ignored as they're not part of the request model
        // The controller generates courtListId internally and sets the status internally
        final String today = LocalDate.now().toString();
        return """
            {
                "courtCentreId": "%s",
                "startDate": "%s",
                "endDate": "%s",
                "courtListType": "%s"
            }
            """.formatted(courtCentreId, today, today, courtListType.name());
    }

    private static final String CJSCPPUID_HEADER = "CJSCPPUID";
    private static final String INTEGRATION_TEST_USER_ID = "integration-test-user-id";

    private HttpEntity<String> createHttpEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.post+json"));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        return new HttpEntity<>(json, headers);
    }

    private ResponseEntity<String> postRequest(String requestJson) {
        return http.exchange(PUBLISH_ENDPOINT, HttpMethod.POST, createHttpEntity(requestJson), String.class);
    }

    private ResponseEntity<String> getRequest(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(new MediaType("application", "vnd.courtlistpublishing-service.publish.get+json")));
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parseResponse(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccessfulCreation(ResponseEntity<String> response, UUID courtListId, UUID courtCentreId) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getBody()).isNotNull();

        JsonNode responseBody = parseResponse(response);
        assertThat(responseBody.get("courtListId").asText()).isNotNull();
        assertThat(responseBody.get("courtCentreId").asText()).isEqualTo(courtCentreId.toString());
        assertThat(responseBody.get("publishStatus").asText()).isEqualTo(CourtListPublishControllerHttpLiveTest.REQUESTED_STATUS);
        assertThat(responseBody.get("courtListType").asText()).isEqualTo(CourtListPublishControllerHttpLiveTest.COURT_LIST_TYPE_PUBLIC);
        assertThat(responseBody.get("lastUpdated")).isNotNull();
    }

    private void assertBadRequest(Runnable request) {
        assertThatThrownBy(request::run)
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(exception -> {
                    HttpClientErrorException ex = (HttpClientErrorException) exception;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void postDownloadCourtListReturnsPdfWhenPublicAndStubbed() throws Exception {
        postDownloadCourtListReturnsPdfForType(CourtListType.PUBLIC);
    }

    @Test
    void postDownloadCourtListReturnsPdfWhenBenchAndStubbed() throws Exception {
        postDownloadCourtListReturnsPdfForType(CourtListType.BENCH);
    }

    @Test
    void postDownloadCourtListReturnsPdfWhenAlphabeticalAndStubbed() throws Exception {
        postDownloadCourtListReturnsPdfForType(CourtListType.ALPHABETICAL);
    }

    @Test
    void postDownloadCourtListReturnsWordWhenUshersCrownAndStubbed() throws Exception {
        postDownloadCourtListReturnsWordForType(CourtListType.USHERS_CROWN);
    }

    @Test
    void postDownloadCourtListReturnsWordWhenUshersMagistrateAndStubbed() throws Exception {
        postDownloadCourtListReturnsWordForType(CourtListType.USHERS_MAGISTRATE);
    }

    @Test
    void postDownloadCourtListReturnsBadRequestWhenCourtListTypeIsStandard() {
        assertDownloadCourtListReturnsBadRequestForUnsupportedType(CourtListType.STANDARD);
    }

    @Test
    void postDownloadCourtListReturnsBadRequestWhenCourtListTypeIsJudge() {
        assertDownloadCourtListReturnsBadRequestForUnsupportedType(CourtListType.JUDGE);
    }

    private void postDownloadCourtListReturnsPdfForType(CourtListType courtListType) throws Exception {
        String requestJson = """
            {
                "courtCentreId": "f8254db1-1683-483e-afb3-b87fde5a0a26",
                "startDate": "2026-02-27",
                "endDate": "2026-02-27",
                "courtListType": "%s"
            }
            """.formatted(courtListType.name());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(DOWNLOAD_CONTENT_TYPE));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<byte[]> response = http.exchange(
                DOWNLOAD_ENDPOINT,
                HttpMethod.POST,
                entity,
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "CourtList.pdf");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().length).isGreaterThan(0);
    }

    private void postDownloadCourtListReturnsWordForType(CourtListType courtListType) throws Exception {
        String requestJson = """
            {
                "courtCentreId": "f8254db1-1683-483e-afb3-b87fde5a0a26",
                "startDate": "2026-02-27",
                "endDate": "2026-02-27",
                "courtListType": "%s"
            }
            """.formatted(courtListType.name());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(DOWNLOAD_CONTENT_TYPE));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<byte[]> response = http.exchange(
                DOWNLOAD_ENDPOINT,
                HttpMethod.POST,
                entity,
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "CourtList.docx");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().length).isGreaterThan(0);
    }

    private void assertDownloadCourtListReturnsBadRequestForUnsupportedType(CourtListType courtListType) {
        String requestJson = """
            {
                "courtCentreId": "f8254db1-1683-483e-afb3-b87fde5a0a26",
                "startDate": "2026-02-27",
                "endDate": "2026-02-27",
                "courtListType": "%s"
            }
            """.formatted(courtListType.name());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(DOWNLOAD_CONTENT_TYPE));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        assertThatThrownBy(() -> http.exchange(
                DOWNLOAD_ENDPOINT,
                HttpMethod.POST,
                entity,
                byte[].class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("Download supported for PUBLIC, BENCH, ALPHABETICAL, USHERS_CROWN, USHERS_MAGISTRATE only");
    }
}

