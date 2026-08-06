package uk.gov.hmcts.cp.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.services.JsonSchemaValidatorService;
import uk.gov.hmcts.cp.services.PublicationSchema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the SJP → CaTH publish flow.
 *
 * <p>Verifies that POST /api/court-list-publish/sjp/publishCourtList:
 * <ul>
 *   <li>Transforms the SJP payload into the expected CaTH JSON structure</li>
 *   <li>Sends the correct DtsMeta headers (x-list-type, x-sensitivity, x-language, x-court-id, etc.)</li>
 *   <li>Handles both SJP_PUBLIC_LIST and SJP_PRESS_LIST list types</li>
 *   <li>Skips the CaTH call when readyCases is empty</li>
 * </ul>
 *
 * <p>Requires the app + WireMock running (see docker-compose integration profile).
 */
public class SjpCaTHPayloadIntegrationTest extends AbstractTest {

    private static final String BASE_URL =
            System.getProperty("app.baseUrl", "http://localhost:8082/courtlistpublishing-service");
    private static final String SJP_PUBLISH_ENDPOINT =
            BASE_URL + "/api/court-list-publish/sjp/publishCourtList";

    private static final String CJSCPPUID_HEADER = "CJSCPPUID";
    private static final String INTEGRATION_TEST_USER_ID = "integration-test-user-id";

    /** Poll WireMock for up to 10 s after a synchronous SJP POST. */
    private static final long CATH_WAIT_MS = 10_000;
    private static final long POLL_MS = 200;

    private final RestTemplate http = new RestTemplate();
    private final JsonSchemaValidatorService schemaValidator = new JsonSchemaValidatorService();

    @BeforeEach
    void resetWireMockBefore() {
        resetWireMock();
    }

    // ── Public list ──────────────────────────────────────────────────────────

    @Test
    void publishPublicList_postsCorrectPayloadStructureToCaTH() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "requestType": "FULL",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "courtIdNumeric": "325",
                "readyCases": [
                  {
                    "caseUrn": "URN-PUBLIC-001",
                    "defendantName": "Jane Smith",
                    "firstName": "J",
                    "lastName": "Smith",
                    "dateOfBirth": "1990-01-15",
                    "addressLine1": "10 Main Street",
                    "town": "Manchester",
                    "postcode": "M1",
                    "prosecutorName": "Crown Prosecution Service",
                    "sjpOffences": [
                      {"title": "Speeding", "wording": "Drove at 50mph in a 30mph zone"}
                    ]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        ResponseEntity<String> response = postSjpRequest(requestJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.get("listType").asText()).isEqualTo("SJP_PUBLIC_LIST");

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        JsonNode cathPayload = parseCaTHBody(cathReq);

        // ── document ──────────────────────────────────────────────────────────
        JsonNode doc = cathPayload.path("document");
        assertThat(doc.path("documentName").asText()).isEqualTo("SJP Public list");
        assertThat(doc.path("publicationDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        assertThat(doc.path("version").asText()).isEqualTo("1.0");

        // ── courtLists/0/courtHouse/courtRoom/0/session/0/sittings/0/hearing/0 ──
        JsonNode hearing0 = firstHearing(cathPayload);
        assertThat(hearing0.isMissingNode()).as("hearing[0] should be present").isFalse();

        // parties
        JsonNode parties = hearing0.path("party");
        assertThat(parties.isArray()).isTrue();
        assertThat(parties.size()).isGreaterThanOrEqualTo(2);

        JsonNode prosecutor = findPartyByRole(parties, "PROSECUTOR");
        assertThat(prosecutor.isMissingNode()).as("PROSECUTOR party should be present").isFalse();
        assertThat(prosecutor.path("organisationDetails").path("organisationName").asText())
                .isEqualTo("Crown Prosecution Service");

        JsonNode accused = findPartyByRole(parties, "ACCUSED");
        assertThat(accused.isMissingNode()).as("ACCUSED party should be present").isFalse();
        JsonNode individualDetails = accused.path("individualDetails");
        assertThat(individualDetails.path("individualForenames").asText()).isEqualTo("J");
        assertThat(individualDetails.path("individualSurname").asText()).isEqualTo("Smith");
        assertThat(individualDetails.path("dateOfBirth").asText()).isEqualTo("1990-01-15");
        JsonNode address = individualDetails.path("address");
        assertThat(address.path("line").get(0).asText()).isEqualTo("10 Main Street");
        assertThat(address.path("town").asText()).isEqualTo("Manchester");
        assertThat(address.path("postCode").asText()).isEqualTo("M1");

        // offences
        JsonNode offences = hearing0.path("offence");
        assertThat(offences.isArray()).isTrue();
        assertThat(offences.size()).isEqualTo(1);
        assertThat(offences.get(0).path("offenceTitle").asText()).isEqualTo("Speeding");
        assertThat(offences.get(0).path("offenceWording").asText()).isEqualTo("Drove at 50mph in a 30mph zone");
        // reportingRestriction not present on public list
        assertThat(offences.get(0).has("reportingRestriction")).isFalse();

        // case URN (serialised as "case" by @JsonProperty)
        JsonNode cases = hearing0.path("case");
        assertThat(cases.isArray()).isTrue();
        assertThat(cases.get(0).path("caseUrn").asText()).isEqualTo("URN-PUBLIC-001");

        // ── full schema validation ────────────────────────────────────────────
        assertThatCode(() -> schemaValidator.validate(bodyAsString(cathReq), PublicationSchema.SJP_PUBLIC))
                .as("CaTH payload must be valid against the SJP public schema")
                .doesNotThrowAnyException();

        // ── DtsMeta headers ───────────────────────────────────────────────────
        assertThat(header(cathReq, "x-list-type")).isEqualTo("SJP_PUBLIC_LIST");
        assertThat(header(cathReq, "x-sensitivity")).isEqualTo("PUBLIC");
        assertThat(header(cathReq, "x-language")).isEqualTo("ENGLISH");
        assertThat(header(cathReq, "x-court-id")).isEqualTo("325");
        assertThat(header(cathReq, "x-provenance")).isEqualTo("COMMON_PLATFORM");
        assertThat(header(cathReq, "x-type")).isEqualTo("LIST");
        assertThat(header(cathReq, "x-content-date")).isNotBlank();
        assertThat(header(cathReq, "x-display-from")).isNotBlank();
        assertThat(header(cathReq, "x-display-to")).isNotBlank();
    }

    // ── Press list ───────────────────────────────────────────────────────────

    @Test
    void publishPressList_postsCorrectPayloadWithClassifiedSensitivityAndReportingRestriction() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PRESS_LIST",
              "requestType": "FULL",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "courtIdNumeric": "100",
                "readyCases": [
                  {
                    "caseUrn": "URN-PRESS-001",
                    "defendantName": "Bob Jones",
                    "firstName": "Bob",
                    "lastName": "Jones",
                    "prosecutorName": "CPS",
                    "sjpOffences": [
                      {"title": "Littering", "wording": "Dropped litter in a public place", "reportingRestriction": true}
                    ]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        ResponseEntity<String> response = postSjpRequest(requestJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.get("listType").asText()).isEqualTo("SJP_PRESS_LIST");

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        JsonNode cathPayload = parseCaTHBody(cathReq);

        // document
        assertThat(cathPayload.path("document").path("documentName").asText()).isEqualTo("SJP Press list");

        // reportingRestriction present on press list offence
        JsonNode offence0 = firstHearing(cathPayload).path("offence").path(0);
        assertThat(offence0.path("offenceTitle").asText()).isEqualTo("Littering");
        assertThat(offence0.path("reportingRestriction").asBoolean()).isTrue();

        // ── full schema validation ────────────────────────────────────────────
        assertThatCode(() -> schemaValidator.validate(bodyAsString(cathReq), PublicationSchema.SJP_PRESS))
                .as("CaTH payload must be valid against the SJP press schema")
                .doesNotThrowAnyException();

        // ── DtsMeta headers ───────────────────────────────────────────────────
        assertThat(header(cathReq, "x-list-type")).isEqualTo("SJP_PRESS_LIST");
        assertThat(header(cathReq, "x-sensitivity")).isEqualTo("CLASSIFIED");
        assertThat(header(cathReq, "x-court-id")).isEqualTo("100");
    }

    // ── Welsh language from isWelsh ──────────────────────────────────────────

    @Test
    void publishPublicList_sendsWelshLanguageHeader_whenIsWelshTrue() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "isWelsh": true,
                "readyCases": [
                  {
                    "caseUrn": "URN-WELSH-001",
                    "defendantName": "Sion Evans",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence", "wording": "Details"}]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        postSjpRequest(requestJson);

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        assertThat(header(cathReq, "x-language")).isEqualTo("WELSH");
    }

    @Test
    void publishPublicList_sendsEnglishLanguageHeader_whenIsWelshFalse() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "isWelsh": false,
                "readyCases": [
                  {
                    "caseUrn": "URN-EN-001",
                    "defendantName": "Alice Brown",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence", "wording": "Details"}]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        postSjpRequest(requestJson);

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        assertThat(header(cathReq, "x-language")).isEqualTo("ENGLISH");
    }

    @Test
    void publishPublicList_explicitLanguageOverridesIsWelsh() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "language": "ENGLISH",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "isWelsh": true,
                "readyCases": [
                  {
                    "caseUrn": "URN-OVERRIDE-001",
                    "defendantName": "Override Test",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence", "wording": "Details"}]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        postSjpRequest(requestJson);

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        assertThat(header(cathReq, "x-language")).isEqualTo("ENGLISH");
    }

    // ── courtId fallback ─────────────────────────────────────────────────────

    @Test
    void publishPublicList_sendsDefaultCourtId_whenCourtIdNumericAbsent() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "readyCases": [
                  {
                    "caseUrn": "URN-NOID-001",
                    "defendantName": "No Id",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence", "wording": "Details"}]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        postSjpRequest(requestJson);

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        assertThat(header(cathReq, "x-court-id")).isEqualTo("0");
    }

    // ── Multiple readyCases ──────────────────────────────────────────────────

    @Test
    void publishPublicList_producesOneHearingPerReadyCase() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "readyCases": [
                  {
                    "caseUrn": "URN-MULTI-001",
                    "defendantName": "First Defendant",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence A", "wording": "Details A"}]
                  },
                  {
                    "caseUrn": "URN-MULTI-002",
                    "defendantName": "Second Defendant",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence B", "wording": "Details B"}]
                  },
                  {
                    "caseUrn": "URN-MULTI-003",
                    "defendantName": "Third Defendant",
                    "prosecutorName": "CPS",
                    "sjpOffences": [{"title": "Offence C", "wording": "Details C"}]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        postSjpRequest(requestJson);

        JsonNode cathPayload = parseCaTHBody(waitForAdditionalCaTHRequest(cathCountBefore));
        JsonNode hearings = cathPayload
                .path("courtLists").path(0)
                .path("courtHouse").path("courtRoom").path(0)
                .path("session").path(0)
                .path("sittings").path(0)
                .path("hearing");

        assertThat(hearings.isArray()).isTrue();
        assertThat(hearings.size()).isEqualTo(3);
        assertThat(hearings.get(0).path("case").path(0).path("caseUrn").asText()).isEqualTo("URN-MULTI-001");
        assertThat(hearings.get(1).path("case").path(0).path("caseUrn").asText()).isEqualTo("URN-MULTI-002");
        assertThat(hearings.get(2).path("case").path(0).path("caseUrn").asText()).isEqualTo("URN-MULTI-003");
    }

    // ── Empty readyCases — no CaTH call ──────────────────────────────────────

    @Test
    void publishPublicList_returns200Accepted_andSkipsCaTH_whenReadyCasesEmpty() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "readyCases": []
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        ResponseEntity<String> response = postSjpRequest(requestJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.get("message").asText()).contains("nothing to publish");

        // Brief pause then confirm WireMock received no new CaTH requests
        Thread.sleep(1000);
        assertThat(listSjpCaTHRequests().size())
                .as("CaTH should not be called when readyCases is empty")
                .isEqualTo(cathCountBefore);
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void publish_returns400_whenListPayloadMissing() {
        String requestJson = """
            { "listType": "SJP_PUBLIC_LIST" }
            """;

        assertThatThrownBy(() -> postSjpRequest(requestJson))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex ->
                        assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void publish_returns400_whenListTypeMissing() {
        assertThatThrownBy(() -> postSjpRequest("{}"))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex ->
                        assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void publishPublicList_sanitizesWafBlockedPatternsAndHtmlFromCaTHPayload() throws Exception {
        String requestJson = """
            {
              "listType": "SJP_PUBLIC_LIST",
              "listPayload": {
                "generatedDateAndTime": "2025-06-01T09:00:00",
                "readyCases": [
                  {
                    "caseUrn": "URN-SANITIZE-001",
                    "defendantName": "Jane Smith",
                    "lastName": "Smith../Suffix",
                    "town": "Greater <br/> Manchester",
                    "prosecutorName": "CPS ../Regional",
                    "sjpOffences": [
                      {"title": "Speeding <script>", "wording": "Drove at ../50mph in a 30 zone"}
                    ]
                  }
                ]
              }
            }
            """;

        int cathCountBefore = listSjpCaTHRequests().size();
        ResponseEntity<String> response = postSjpRequest(requestJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("status").asText()).isEqualTo("ACCEPTED");

        JsonNode cathReq = waitForAdditionalCaTHRequest(cathCountBefore);
        JsonNode cathPayload = parseCaTHBody(cathReq);
        JsonNode hearing0 = firstHearing(cathPayload);

        // HTML tags and WAF path-traversal sequences must be stripped from all text fields

        JsonNode prosecutor = findPartyByRole(hearing0.path("party"), "PROSECUTOR");
        assertThat(prosecutor.path("organisationDetails").path("organisationName").asText())
                .doesNotContain("../")
                .isEqualTo("CPS Regional");

        JsonNode accused = findPartyByRole(hearing0.path("party"), "ACCUSED");
        JsonNode individualDetails = accused.path("individualDetails");
        assertThat(individualDetails.path("individualSurname").asText())
                .doesNotContain("../")
                .isEqualTo("Smith Suffix");
        assertThat(individualDetails.path("address").path("town").asText())
                .doesNotContain("<br/>")
                .isEqualTo("Greater Manchester");

        JsonNode offence0 = hearing0.path("offence").path(0);
        assertThat(offence0.path("offenceTitle").asText())
                .doesNotContain("<script>")
                .isEqualTo("Speeding");
        assertThat(offence0.path("offenceWording").asText())
                .doesNotContain("../")
                .isEqualTo("Drove at 50mph in a 30 zone");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> postSjpRequest(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.courtlistpublishing-service.sjp.post+json"));
        headers.set(CJSCPPUID_HEADER, INTEGRATION_TEST_USER_ID);
        return http.exchange(SJP_PUBLISH_ENDPOINT, HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode waitForAdditionalCaTHRequest(int previousCount) throws Exception {
        long deadline = System.currentTimeMillis() + CATH_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> requests = listSjpCaTHRequests();
            if (requests.size() > previousCount) {
                return requests.getLast();
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("No new CaTH POST received within " + CATH_WAIT_MS + "ms. "
                + "Previous count=" + previousCount + ", current=" + listSjpCaTHRequests().size());
    }

    private List<JsonNode> listSjpCaTHRequests() throws Exception {
        ResponseEntity<String> admin = http.getForEntity(WIREMOCK_ADMIN_REQUESTS, String.class);
        assertThat(admin.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = objectMapper.readTree(admin.getBody());
        JsonNode requests = root.has("requests") ? root.get("requests") : root;

        List<JsonNode> out = new ArrayList<>();
        for (JsonNode entry : requests) {
            JsonNode req = entry.has("request") ? entry.get("request") : entry;
            if (!"POST".equalsIgnoreCase(req.path("method").asText())) {
                continue;
            }
            String url = req.has("url") ? req.get("url").asText("") : req.path("absoluteUrl").asText("");
            if (!url.contains(CATH_PUBLICATION_URL_PATH)) {
                continue;
            }
            String body = bodyAsString(req);
            if (body != null && !body.isBlank()) {
                out.add(req);
            }
        }
        return out;
    }

    private JsonNode parseCaTHBody(JsonNode req) throws Exception {
        String body = bodyAsString(req);
        assertThat(body).as("CaTH request body should not be empty").isNotBlank();
        return objectMapper.readTree(body);
    }

    /** Returns the first hearing at courtLists/0/courtHouse/courtRoom/0/session/0/sittings/0/hearing/0. */
    private static JsonNode firstHearing(JsonNode cathPayload) {
        return cathPayload
                .path("courtLists").path(0)
                .path("courtHouse").path("courtRoom").path(0)
                .path("session").path(0)
                .path("sittings").path(0)
                .path("hearing").path(0);
    }

    private static JsonNode findPartyByRole(JsonNode parties, String role) {
        for (JsonNode party : parties) {
            if (role.equals(party.path("partyRole").asText())) {
                return party;
            }
        }
        return objectMapper.missingNode();
    }

    private static final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    private static String bodyAsString(JsonNode req) {
        if (req.has("body") && !req.get("body").isNull()) {
            String text = req.get("body").asText("");
            if (!text.isBlank()) return text;
        }
        if (req.has("bodyAsBase64") && !req.get("bodyAsBase64").isNull()) {
            String b64 = req.get("bodyAsBase64").asText("");
            if (!b64.isBlank()) {
                return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String header(JsonNode req, String name) {
        JsonNode headers = req.path("headers");
        if (!headers.isObject()) return null;
        // exact match first
        JsonNode node = headers.get(name);
        if (node == null) {
            // case-insensitive fallback
            for (var it = headers.fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getKey().equalsIgnoreCase(name)) {
                    node = e.getValue();
                    break;
                }
            }
        }
        if (node == null) return null;
        if (node.isTextual()) return node.asText();
        if (node.isArray() && !node.isEmpty()) return node.get(0).asText();
        if (node.isObject()) {
            JsonNode values = node.get("values");
            if (values != null && values.isArray() && !values.isEmpty()) return values.get(0).asText();
            JsonNode value = node.get("value");
            if (value != null) return value.asText();
        }
        return node.asText();
    }
}
