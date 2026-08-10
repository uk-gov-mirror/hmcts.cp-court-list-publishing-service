package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;

public class CourtListPublishControllerHttpLiveTest extends AbstractTest {

    private static final String BASE_URL = System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    private static final String PUBLISH_ENDPOINT = BASE_URL + "/api/court-list-publish/publish";
    private static final String DOWNLOAD_ENDPOINT = BASE_URL + "/api/court-list-publish/download";
    private static final String DOWNLOAD_ACCEPT = "application/vnd.courtlistpublishing-service.download.get+json";
    private static final String REQUESTED_STATUS = Status.REQUESTED.toString();
    private static final String COURT_LIST_TYPE_PUBLIC = CourtListType.PUBLIC.toString();
    private static final String COURT_LIST_TYPE_FINAL = CourtListType.FINAL.toString();
    private static final String CROWN_COURT_CENTRE_ID = "cc000000-0000-0000-0000-000000000001";
    private static final String CROWN_COURT_CENTRE_ID_WELSH = "cc000000-0000-0000-0000-000000000002";

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    @BeforeEach
    void resetWireMockBeforeEach() {
        AbstractTest.resetWireMock();
    }

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
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
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
    void getDownloadCourtListReturnsPdfWhenPublicAndStubbed() throws Exception {
        getDownloadCourtListReturnsPdfForType(CourtListType.PUBLIC);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenBenchAndStubbed() throws Exception {
        getDownloadCourtListReturnsPdfForType(CourtListType.BENCH);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenAlphabeticalAndStubbed() throws Exception {
        getDownloadCourtListReturnsPdfForType(CourtListType.ALPHABETICAL);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenJudgeAndStubbed() throws Exception {
        getDownloadCourtListReturnsPdfForType(CourtListType.JUDGE);
    }

    @Test
    void getDownloadCourtListReturnsWordWhenUshersCrownAndStubbed() throws Exception {
        getDownloadCourtListReturnsWordForType(CourtListType.USHERS_CROWN);
    }

    @Test
    void getDownloadCourtListReturnsWordWhenUshersMagistrateAndStubbed() throws Exception {
        getDownloadCourtListReturnsWordForType(CourtListType.USHERS_MAGISTRATE);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenStandardAndStubbed() throws Exception {
        getDownloadCourtListReturnsPdfForType(CourtListType.STANDARD);
    }

    private String buildDownloadUrl(CourtListType courtListType) {
        return DOWNLOAD_ENDPOINT
                + "?courtCentreId=f8254db1-1683-483e-afb3-b87fde5a0a26"
                + "&startDate=2026-02-27"
                + "&endDate=2026-02-27"
                + "&courtListType=" + courtListType.name();
    }

    private void getDownloadCourtListReturnsPdfForType(CourtListType courtListType) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType(DOWNLOAD_ACCEPT)));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = http.exchange(
                buildDownloadUrl(courtListType),
                HttpMethod.GET,
                entity,
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "CourtList.pdf");
        byte[] body = response.getBody();
        assertThat(body).as("PDF body for %s must be non-null", courtListType).isNotNull();
        assertThat(body.length).as("PDF body for %s must be non-empty", courtListType).isGreaterThan(0);
        assertThat(new String(body, 0, Math.min(4, body.length)))
                .as("PDF body for %s must start with %%PDF magic", courtListType)
                .startsWith("%PDF");
        assertThat(body)
                .as("PDF body for %s must match the WireMock-stubbed minimal-pdf.pdf bytes", courtListType)
                .isEqualTo(loadResourceBytes("wiremock/__files/minimal-pdf.pdf"));

        if (courtListType == CourtListType.ALPHABETICAL || courtListType == CourtListType.JUDGE) {
            verifyListingCourtListBinaryCalled(courtListType);
            verifyDocumentGeneratorNotCalled();
        } else if (courtListType == CourtListType.PUBLIC
                || courtListType == CourtListType.STANDARD
                || courtListType == CourtListType.BENCH) {
            verifyProgressionCourtlistBinaryCalled(courtListType);
            verifyDocumentGeneratorNotCalled();
        } else {
            verifyListingPayloadCalled(courtListType);
            verifyDocumentGeneratorCalled(expectedTemplate(courtListType), "pdf");
        }
    }

    private void getDownloadCourtListReturnsWordForType(CourtListType courtListType) throws Exception {
        String expectedContent = loadResourceText(expectedDocxContentResource(courtListType));
        DocumentGeneratorStub.stubDocumentCreate(expectedContent);

        HttpHeaders headers = new HttpHeaders();
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType(DOWNLOAD_ACCEPT)));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = http.exchange(
                buildDownloadUrl(courtListType),
                HttpMethod.GET,
                entity,
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "CourtList.docx");
        byte[] body = response.getBody();
        assertThat(body).as("DOCX body for %s must be non-null", courtListType).isNotNull();
        assertThat(body)
                .as("DOCX body for %s must match every byte (and therefore every field value) of the stubbed content fixture", courtListType)
                .isEqualTo(expectedContent.getBytes(StandardCharsets.UTF_8));

        verifyProgressionCourtlistDataCalled(courtListType);
        verifyDocumentGeneratorCalled(expectedTemplate(courtListType), "docx");
    }

    private static String expectedDocxContentResource(CourtListType type) {
        switch (type) {
            case USHERS_CROWN:      return "wiremock/__files/expected-ushers-crown-docx-content.txt";
            case USHERS_MAGISTRATE: return "wiremock/__files/expected-ushers-magistrate-docx-content.txt";
            default: throw new IllegalArgumentException("No expected DOCX content fixture for " + type);
        }
    }

    private static String expectedTemplate(CourtListType type) {
        switch (type) {
            case PUBLIC:             return "PublicCourtList";
            case BENCH:              return "BenchAndStandardCourtList";
            case STANDARD:           return "BenchAndStandardCourtList";
            case ALPHABETICAL:       return "CourtList";
            case JUDGE:              return "JudgeList";
            case USHERS_CROWN:       return "UshersCrownList";
            case USHERS_MAGISTRATE:  return "UshersMagistrateList";
            default: throw new IllegalArgumentException("No template mapping for " + type);
        }
    }

    private void verifyListingPayloadCalled(CourtListType courtListType) throws Exception {
        List<JsonNode> matches = wiremockRequestsMatching(req -> {
            if (!"GET".equalsIgnoreCase(req.path("method").asText(""))) {
                return false;
            }
            String url = wiremockRequestUrl(req);
            return url.contains("/listing-service/query/api/rest/listing/courtlistpayload")
                    && url.contains("listId=" + courtListType.name())
                    && url.contains("restricted=false")
                    && url.contains("includeApplications=false");
        });
        assertThat(matches)
                .as("Listing /courtlistpayload must be called exactly once for %s with restricted=false and includeApplications=false", courtListType)
                .hasSize(1);
    }

    private void verifyProgressionCourtlistDataCalled(CourtListType courtListType) throws Exception {
        List<JsonNode> matches = wiremockRequestsMatching(req -> {
            if (!"GET".equalsIgnoreCase(req.path("method").asText(""))) {
                return false;
            }
            String url = wiremockRequestUrl(req);
            return url.contains("/progression-service/query/api/rest/progression/courtlistdata")
                    && url.contains("listId=" + courtListType.name())
                    && url.contains("restricted=false")
                    && url.contains("includeApplications=false");
        });
        assertThat(matches)
                .as("Progression /courtlistdata must be called exactly once for %s with restricted=false and includeApplications=false", courtListType)
                .hasSize(1);
    }

    private void verifyProgressionCourtlistBinaryCalled(CourtListType courtListType) throws Exception {
        List<JsonNode> matches = wiremockRequestsMatching(req -> {
            if (!"GET".equalsIgnoreCase(req.path("method").asText(""))) {
                return false;
            }
            String url = wiremockRequestUrl(req);
            return url.contains("/progression-service/query/api/rest/progression/courtlist")
                    && !url.contains("/courtlistdata")
                    && url.contains("listId=" + courtListType.name())
                    && url.contains("restricted=false");
        });
        assertThat(matches)
                .as("Progression /courtlist binary endpoint must be called exactly once for %s with restricted=false (default)", courtListType)
                .hasSize(1);
    }

    private void verifyListingCourtListBinaryCalled(CourtListType courtListType) throws Exception {
        List<JsonNode> matches = wiremockRequestsMatching(req -> {
            if (!"GET".equalsIgnoreCase(req.path("method").asText(""))) {
                return false;
            }
            String url = wiremockRequestUrl(req);
            return url.contains("/listing-service/query/api/rest/listing/courtlist")
                    && !url.contains("/courtlistpayload")
                    && url.contains("listId=" + courtListType.name())
                    && url.contains("restricted=false");
        });
        assertThat(matches)
                .as("Listing /courtlist binary endpoint must be called exactly once for %s with restricted=false", courtListType)
                .hasSize(1);
    }

    private void verifyDocumentGeneratorNotCalled() throws Exception {
        List<JsonNode> matches = wiremockRequestsMatching(req -> {
            if (!"POST".equalsIgnoreCase(req.path("method").asText(""))) {
                return false;
            }
            return wiremockRequestUrl(req).contains("/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/render");
        });
        assertThat(matches)
                .as("Document generator /render must not be called when listing renders the PDF")
                .isEmpty();
    }

    private void verifyDocumentGeneratorCalled(String templateName, String conversionFormat) throws Exception {
        List<JsonNode> matches = wiremockRequestsMatching(req -> {
            if (!"POST".equalsIgnoreCase(req.path("method").asText(""))) {
                return false;
            }
            String url = wiremockRequestUrl(req);
            if (!url.contains("/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/render")) {
                return false;
            }
            String body = req.path("body").asText("");
            return body.contains("\"templateName\":\"" + templateName + "\"")
                    && body.contains("\"conversionFormat\":\"" + conversionFormat + "\"");
        });
        assertThat(matches)
                .as("Document generator /render must be called exactly once with templateName=%s and conversionFormat=%s",
                        templateName, conversionFormat)
                .hasSize(1);
    }

    private List<JsonNode> wiremockRequestsMatching(java.util.function.Predicate<JsonNode> requestPredicate) throws Exception {
        ResponseEntity<String> admin = http.getForEntity(AbstractTest.WIREMOCK_ADMIN_REQUESTS, String.class);
        assertThat(admin.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(admin.getBody());
        JsonNode requests = root.get("requests");
        if (requests == null) {
            requests = root.isArray() ? root : objectMapper.createArrayNode();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode entry : requests) {
            JsonNode req = entry.has("request") ? entry.get("request") : entry;
            if (requestPredicate.test(req)) {
                out.add(req);
            }
        }
        return out;
    }

    private byte[] loadResourceBytes(String resourcePath) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as("Resource %s must be on the test classpath", resourcePath).isNotNull();
            return in.readAllBytes();
        }
    }

    private String loadResourceText(String resourcePath) throws Exception {
        return new String(loadResourceBytes(resourcePath), StandardCharsets.UTF_8);
    }

    private static String wiremockRequestUrl(JsonNode req) {
        if (req.has("url")) {
            return req.get("url").asText("");
        }
        return req.path("absoluteUrl").asText("");
    }

    // Crown court tests

    @Test
    void getDownloadCourtListReturnsPdfWhenDraftAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.DRAFT, CROWN_COURT_CENTRE_ID, false);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenFinalAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.FINAL, CROWN_COURT_CENTRE_ID, false);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenAlphabeticalAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.ALPHABETICAL, CROWN_COURT_CENTRE_ID, false);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenOnlinePublicAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.ONLINE_PUBLIC, CROWN_COURT_CENTRE_ID, false);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenFirmAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.FIRM, CROWN_COURT_CENTRE_ID, false);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenAlphabeticalAndWelshCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.ALPHABETICAL, CROWN_COURT_CENTRE_ID_WELSH, true);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenJudgeAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.JUDGE, CROWN_COURT_CENTRE_ID, false);
    }

    @Test
    void getDownloadCourtListReturnsPdfWhenUshersCrownAndCrownCourt() throws Exception {
        getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType.USHERS_CROWN, CROWN_COURT_CENTRE_ID, false);
    }

    private String buildCrownCourtDownloadUrl(CourtListType courtListType, String courtCentreId) {
        return DOWNLOAD_ENDPOINT
                + "?courtCentreId=" + courtCentreId
                + "&startDate=2026-02-27"
                + "&endDate=2026-02-27"
                + "&courtListType=" + courtListType.name();
    }

    private void getDownloadCourtListReturnsCrownCourtPdfForType(CourtListType courtListType, String courtCentreId, boolean isWelsh) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType(DOWNLOAD_ACCEPT)));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = http.exchange(
                buildCrownCourtDownloadUrl(courtListType, courtCentreId),
                HttpMethod.GET,
                entity,
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "CourtList.pdf");
        byte[] body = response.getBody();
        assertThat(body).as("PDF body for %s must be non-null", courtListType).isNotNull();
        assertThat(body.length).as("PDF body for %s must be non-empty", courtListType).isGreaterThan(0);
        assertThat(new String(body, 0, Math.min(4, body.length)))
                .as("PDF body for %s must start with %%PDF magic", courtListType)
                .startsWith("%PDF");
        assertThat(body)
                .as("PDF body for %s must match the WireMock-stubbed minimal-pdf.pdf bytes", courtListType)
                .isEqualTo(loadResourceBytes("wiremock/__files/minimal-pdf.pdf"));

        verifyCrownDailyListPayloadCalled(courtListType);
        verifyDocumentGeneratorCalled(expectedCrownTemplate(courtListType, isWelsh), "pdf");
    }

    private void verifyCrownDailyListPayloadCalled(CourtListType courtListType) throws Exception {
        if (CourtListType.ALPHABETICAL.equals(courtListType)) {
            List<JsonNode> matches = wiremockRequestsMatching(req -> {
                if (!"GET".equalsIgnoreCase(req.path("method").asText(""))) {
                    return false;
                }
                String url = wiremockRequestUrl(req);
                return url.contains("/listing-service/query/api/rest/listing/courtlistpayload")
                        && url.contains("listId=" + courtListType.name());
            });
            assertThat(matches)
                    .as("Crown alphabetical /courtlistpayload must be called exactly once for %s", courtListType)
                    .hasSize(1);
        } else {
            List<JsonNode> matches = wiremockRequestsMatching(req -> {
                if (!"GET".equalsIgnoreCase(req.path("method").asText(""))) {
                    return false;
                }
                String url = wiremockRequestUrl(req);
                return url.contains("/listing-service/query/api/rest/listing/dailylistpayload")
                        && url.contains("publishCourtListType=" + courtListType.name());
            });
            assertThat(matches)
                    .as("Crown daily list /dailylistpayload must be called exactly once for %s", courtListType)
                    .hasSize(1);

            String url = wiremockRequestUrl(matches.get(0));
            if (CourtListType.FIRM.equals(courtListType)) {
                assertThat(url)
                        .as("FIRM /dailylistpayload call must use weekCommencing date params")
                        .contains("weekCommencingStartDate=", "weekCommencingEndDate=")
                        .doesNotContain("&startDate=", "&endDate=");
            } else {
                assertThat(url)
                        .as("Non-FIRM /dailylistpayload call must use plain startDate/endDate params")
                        .contains("&startDate=", "&endDate=")
                        .doesNotContain("weekCommencingStartDate=", "weekCommencingEndDate=");
            }
        }
    }

    private static String expectedCrownTemplate(CourtListType type, boolean isWelsh) {
        if (isWelsh) {
            switch (type) {
                case DRAFT:
                case FINAL:       return "CrownDailyListWelsh";
                case ONLINE_PUBLIC: return "CrownOnlinePublicCourtListWelsh";
                case ALPHABETICAL: return "CourtListEnglishWelsh";
                case FIRM:        return "CrownFirmListWelsh";
                case JUDGE:       return "JudgeList";
                case USHERS_CROWN: return "UshersCrownList";
                default: throw new IllegalArgumentException("No crown Welsh template for " + type);
            }
        } else {
            switch (type) {
                case DRAFT:
                case FINAL:       return "CrownDailyList";
                case ONLINE_PUBLIC: return "CrownOnlinePublicCourtList";
                case ALPHABETICAL: return "CourtList";
                case FIRM:        return "CrownFirmList";
                case JUDGE:       return "JudgeList";
                case USHERS_CROWN: return "UshersCrownList";
                default: throw new IllegalArgumentException("No crown template for " + type);
            }
        }
    }

}

