package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.openapi.model.CourtListType.ONLINE_PUBLIC;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class CourtListPublishAndPDFGenerationTaskIntegrationTest extends CourtListIntegrationTestBase {

    private static final long WIREMOCK_STUB_REGISTER_DELAY_MS = 500;

    @Test
    void publishCourtList_shouldQueryAndSendToCaTH_whenValidRequest() throws Exception {
        UUID courtListId = publishOnlinePublicExpectingRequested();
        waitForTaskCompletion(courtListId, TASK_TIMEOUT_MS);

        ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseResponse(statusResponse).get("publishStatus").asText()).isEqualTo("SUCCESSFUL");
    }

    @Test
    void publishCourtList_shouldStillUpdateStatus_whenCaTHEndpointFails() throws Exception {
        addCathFailureStub();
        try {
            UUID courtListId = publishOnlinePublicExpectingRequested();
            waitForTaskCompletion(courtListId, TASK_TIMEOUT_MS);

            ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
            assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode statusBody = parseResponse(statusResponse);
            assertThat(statusBody.get("publishStatus").asText()).isEqualTo("FAILED");
            assertThat(statusBody.has("publishErrorMessage")).isTrue();
            assertThat(statusBody.get("publishErrorMessage").asText()).isNotBlank();
        } finally {
            AbstractTest.resetWireMock();
        }
    }

    @Test
    void publishCourtList_shouldSetFileFailedAndSaveFileErrorMessage_whenPdfGenerationFails() throws Exception {
        addDocumentGeneratorFailureStub();
        try {
            UUID courtListId = publishOnlinePublicExpectingRequested();
            waitForTaskCompletion(courtListId, TASK_TIMEOUT_MS);

            ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
            assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode statusBody = parseResponse(statusResponse);
            assertThat(statusBody.get("fileStatus").asText()).isEqualTo("FAILED");
            assertThat(statusBody.has("fileErrorMessage")).isTrue();
            assertThat(statusBody.get("fileErrorMessage").asText()).isNotBlank();
        } finally {
            AbstractTest.resetWireMock();
        }
    }

    @Test
    void publishCourtList_shouldCreateDbEntry_triggerTask_andUpdateFileUrlWithPdfUrl() throws Exception {
        UUID courtCentreId = UUID.randomUUID();
        String requestJson = createPublishRequestJson(courtCentreId, ONLINE_PUBLIC.toString());
        ResponseEntity<String> publishResponse = postPublishRequest(requestJson);

        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode publishBody = parseResponse(publishResponse);
        UUID courtListId = UUID.fromString(publishBody.get("courtListId").asText());
        assertThat(publishBody.get("publishStatus").asText()).isEqualTo("REQUESTED");

        ResponseEntity<String> immediateStatus = getStatusRequest(courtListId);
        assertThat(immediateStatus.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode immediateBody = parseResponse(immediateStatus);
        assertThat(immediateBody.get("courtListId").asText()).isEqualTo(courtListId.toString());
        assertThat(immediateBody.get("courtCentreId").asText()).isEqualTo(courtCentreId.toString());

        waitForPDFGenerationFileCompletion(courtListId, TASK_TIMEOUT_MS);

        ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode statusBody = parseResponse(statusResponse);
        assertThat(statusBody.get("publishStatus").asText()).isEqualTo("SUCCESSFUL");
        assertThat(statusBody.has("fileId")).isTrue();
        String fileIdStr = statusBody.get("fileId").asText();
        assertThat(fileIdStr).isNotBlank();
        assertThat(UUID.fromString(fileIdStr)).isEqualTo(courtListId);
    }

    @Test
    void publishCourtList_shouldSetPublishFailedWithErrorMessage_whenSchemaValidationFails() throws Exception {
        addSchemaInvalidPayloadStub();
        try {
            UUID courtListId = publishOnlinePublicExpectingRequested();
            waitForTaskCompletion(courtListId, TASK_TIMEOUT_MS);

            ResponseEntity<String> statusResponse = getStatusRequest(courtListId);
            assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode statusBody = parseResponse(statusResponse);
            assertThat(statusBody.get("publishStatus").asText()).isEqualTo("FAILED");
            assertThat(statusBody.has("publishErrorMessage")).isTrue();
            JsonNode errNode = statusBody.get("publishErrorMessage");
            if (!errNode.isNull() && !errNode.asText().isBlank()) {
                assertThat(errNode.asText()).contains("JSON schema validation failed");
            }
        } finally {
            AbstractTest.resetWireMock();
        }
    }

    /** POST + 200 + REQUESTED; returns new {@code courtListId}. */
    private UUID publishOnlinePublicExpectingRequested() throws Exception {
        UUID courtCentreId = UUID.randomUUID();
        ResponseEntity<String> publishResponse =
                postPublishRequest(createPublishRequestJson(courtCentreId, ONLINE_PUBLIC.toString()));
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode publishBody = parseResponse(publishResponse);
        assertThat(publishBody.get("publishStatus").asText()).isEqualTo("REQUESTED");
        return UUID.fromString(publishBody.get("courtListId").asText());
    }

    private void postWireMockMapping(String mappingJson, String label) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(
                WIREMOCK_ADMIN_MAPPINGS,
                HttpMethod.POST,
                new HttpEntity<>(mappingJson, headers),
                String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("WireMock mapping failed (" + label + "): " + response.getStatusCode());
        }
        Thread.sleep(WIREMOCK_STUB_REGISTER_DELAY_MS);
    }

    private void addCathFailureStub() throws Exception {
        String mapping = """
            {
              "request": {
                "method": "POST",
                "urlPath": "%s"
              },
              "response": {
                "status": 500,
                "headers": {"Content-Type": "application/json"},
                "body": "{\\"error\\": \\"Internal Server Error\\"}"
              },
              "priority": 0
            }
            """.formatted(CATH_PUBLICATION_URL_PATH);
        postWireMockMapping(mapping, "CaTH failure");
    }

    private void addSchemaInvalidPayloadStub() throws Exception {
        String mappingJson = """
            {
              "request": {
                "method": "GET",
                "urlPathPattern": "/progression-service/query/api/rest/progression/courtlistdata.*",
                "queryParameters": {"listId": {"equalTo": "ONLINE_PUBLIC"}}
              },
              "response": {
                "status": 200,
                "headers": {"Content-Type": "application/json"},
                "bodyFileName": "court-list-payload-public-schema-invalid.json"
              },
              "priority": 0
            }
            """;
        postWireMockMapping(mappingJson, "schema-invalid progression payload");
    }

    private void addDocumentGeneratorFailureStub() throws Exception {
        String mappingJson = """
            {
              "request": {
                "method": "POST",
                "urlPathPattern": "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/render"
              },
              "response": {
                "status": 500,
                "headers": {"Content-Type": "application/json"},
                "body": "{\\"error\\": \\"PDF generation failed\\"}"
              },
              "priority": 0
            }
            """;
        postWireMockMapping(mappingJson, "document-generator failure");
    }
}
