package uk.gov.hmcts.cp.http;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Helper to add WireMock stubs for the document generator render endpoint.
 */
public final class DocumentGeneratorStub {

    /** Path the app calls (PdfGenerationService.RENDER_PATH). */
    public static final String PATH = "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/render";

    public static final String CONTENT_TYPE = "application/vnd.systemdocgenerator.render+json";

    private static final RestTemplate REST = new RestTemplate();

    private DocumentGeneratorStub() {}

    /**
     * Stubs the document generator to return 200 with the given text as response body (Content-Type: application/pdf).
     * */
    public static void stubDocumentCreate(String documentText) {
        String escaped = documentText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        String json = """
            {
              "request": {
                "method": "POST",
                "urlPathPattern": "%s",
                "headers": { "Content-Type": { "equalTo": "%s" } }
              },
              "response": {
                "status": 200,
                "headers": { "Content-Type": "application/pdf" },
                "body": "%s"
              },
              "priority": 0
            }
            """.formatted(PATH, CONTENT_TYPE, escaped);
        postMapping(json);
    }

    /**
     * Stubs the document generator to return the given status (e.g. 500 for failure tests).
     */
    public static void stubFailure(int status) {
        String json = """
            {
              "request": {
                "method": "POST",
                "urlPathPattern": "%s"
              },
              "response": {
                "status": %d,
                "headers": { "Content-Type": "application/json" },
                "body": "{\\"error\\": \\"Document generator failed\\"}"
              },
              "priority": 0
            }
            """.formatted(PATH, status);
        postMapping(json);
    }

    private static void postMapping(String mappingJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = REST.exchange(
                AbstractTest.WIREMOCK_ADMIN_MAPPINGS,
                HttpMethod.POST,
                new HttpEntity<>(mappingJson, headers),
                String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Failed to add document-generator stub: " + response.getStatusCode());
        }
    }
}
