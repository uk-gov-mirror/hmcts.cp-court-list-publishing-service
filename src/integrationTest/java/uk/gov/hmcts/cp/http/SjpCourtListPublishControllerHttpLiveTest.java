package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Integration tests for SJP court list publish endpoint (/api/court-list-publish/sjp/publishCourtList).
 * Same style as {@link CourtListPublishControllerHttpLiveTest}; requires app running with integration profile.
 */
public class SjpCourtListPublishControllerHttpLiveTest extends AbstractTest {

    private static final String BASE_URL = System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    private static final String SJP_PUBLISH_ENDPOINT = BASE_URL + "/api/court-list-publish/sjp/publishCourtList";

    private static final String CJSCPPUID_HEADER = "CJSCPPUID";
    private static final String INTEGRATION_TEST_USER_ID = "integration-test-user-id";

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    @Test
    void postSjpCourtList_returns400_whenListPayloadOmitted() {
        String requestJson = """
            {
                "listType": "SJP_PUBLIC_LIST"
            }
            """;

        assertThatThrownBy(() -> postSjpRequest(requestJson))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex ->
                    assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void postSjpCourtList_returns200_withAcceptedStatus_whenListPayloadProvided() throws Exception {
        String requestJson = """
            {
                "listType": "SJP_PUBLIC_LIST",
                "listPayload": {
                    "generatedDateAndTime": "2025-03-09T10:00:00",
                    "readyCases": [
                        {
                            "caseUrn": "SJP-INT-TEST-001",
                            "defendantName": "Defendant One",
                            "prosecutorName": "CPS",
                            "sjpOffences": [{"title": "Offence 1", "wording": "Wording 1"}]
                        }
                    ]
                }
            }
            """;

        ResponseEntity<String> response = postSjpRequest(requestJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("listType").asText()).isEqualTo("SJP_PUBLIC_LIST");
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void postSjpCourtList_returns200_forPressListType() throws Exception {
        // SJP_PRESS_LIST handles public.sjp.pending-cases-press-list-generated
        String requestJson = """
            {
                "listType": "SJP_PRESS_LIST",
                "listPayload": {
                    "generatedDateAndTime": "2025-03-09T10:00:00",
                    "readyCases": [
                        {
                            "caseUrn": "SJP-INT-TEST-002",
                            "defendantName": "Defendant Two",
                            "prosecutorName": "CPS",
                            "sjpOffences": [{"title": "Offence 2", "wording": "Wording 2", "reportingRestriction": false}]
                        }
                    ]
                }
            }
            """;

        ResponseEntity<String> response = postSjpRequest(requestJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("listType").asText()).isEqualTo("SJP_PRESS_LIST");
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void postSjpCourtList_returns400_whenListTypeInvalid() {
        // listPayload is fully present and valid, so the 400 is unambiguously caused by the
        // unrecognised listType value, not a missing/invalid payload.
        String requestJson = """
            {
                "listType": "SJP_PUBLISH_LIST",
                "listPayload": {
                    "generatedDateAndTime": "2025-03-09T10:00:00",
                    "readyCases": [
                        {
                            "caseUrn": "SJP-INT-TEST-INVALID",
                            "defendantName": "Defendant Invalid",
                            "prosecutorName": "CPS",
                            "sjpOffences": [{"title": "Offence", "wording": "Wording"}]
                        }
                    ]
                }
            }
            """;

        assertThatThrownBy(() -> postSjpRequest(requestJson))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    // An unrecognised enum value is rejected by Jackson during request-body
                    // deserialization, before the controller (and its ErrorResponse contract via
                    // GlobalExceptionHandler) ever runs - so the body is Spring's own generic
                    // error shape, not this app's usual {error, message, traceId} ErrorResponse.
                    JsonNode body;
                    try {
                        body = objectMapper.readTree(httpEx.getResponseBodyAsString());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    assertThat(body.get("status").asInt()).isEqualTo(400);
                    assertThat(body.get("error").asText()).isEqualTo("Bad Request");
                    assertThat(body.has("message")).isFalse();
                    assertThat(body.has("traceId")).isFalse();
                });
    }

    @Test
    void postSjpCourtList_returns400_whenListTypeMissing() {
        String requestJson = "{}";

        assertThatThrownBy(() -> postSjpRequest(requestJson))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void postSjpCourtList_returns400_whenBodyNull() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.courtlistpublishing-service.sjp.post+json"));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);

        assertThatThrownBy(() -> http.exchange(
                SJP_PUBLISH_ENDPOINT,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    private HttpEntity<String> createSjpHttpEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.courtlistpublishing-service.sjp.post+json"));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        return new HttpEntity<>(json, headers);
    }

    private ResponseEntity<String> postSjpRequest(String requestJson) {
        return http.exchange(
                SJP_PUBLISH_ENDPOINT,
                HttpMethod.POST,
                createSjpHttpEntity(requestJson),
                String.class);
    }
}
