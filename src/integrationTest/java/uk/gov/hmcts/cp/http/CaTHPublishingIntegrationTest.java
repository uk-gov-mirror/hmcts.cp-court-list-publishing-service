package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.openapi.model.CourtListType.ONLINE_PUBLIC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Asserts the CaTH POST body captured by WireMock after an ONLINE_PUBLIC publish completes. */
public class CaTHPublishingIntegrationTest extends CourtListIntegrationTestBase {
    private static final String EXPECTED_CONTENT_DATE_HEADER = "2026-01-20T00:00:00.000Z";
    private static final String EXPECTED_FIRST_SITTING_START = "2026-01-05T10:30:00.000Z";

    @Test
    void publishOnlinePublic_shouldPostTransformedCourtListDocumentToCaTH_withExpectedPartiesAndMetadata() throws Exception {
        UUID courtCentreId = UUID.randomUUID();
        int cathPostsBefore = listCaTHPublicationRequests().size();

        ResponseEntity<String> publishResponse = postPublishRequest(createPublishRequestJson(courtCentreId, ONLINE_PUBLIC.toString()));
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID courtListId = UUID.fromString(parseResponse(publishResponse).get("courtListId").asText());
        waitForTaskCompletion(courtListId, TASK_TIMEOUT_MS);

        JsonNode cathRequest = waitForAdditionalCaTHRequest(cathPostsBefore, CATH_BODY_WAIT_MS);
        String rawBody = requestBodyAsString(cathRequest);
        JsonNode cathPayload = objectMapper.readTree(rawBody);

        assertThat(cathPayload.path("document").path("publicationDate").asText()).isNotBlank();
        assertThat(prosecutingAuthorityOrganisationName(cathPayload)).contains("CITYPF");
        assertThat(firstDefendantForenames(cathPayload)).contains("Tommie");

        JsonNode firstRoom = cathPayload.path("courtLists").path(0).path("courtHouse").path("courtRoom").path(0);
        assertThat(firstRoom.path("courtRoomName").asText()).isEqualTo("Courtroom 01");

        JsonNode firstSitting = firstRoom.path("session").path(0).path("sittings").path(0);
        assertThat(firstSitting.path("sittingStart").asText()).isEqualTo(EXPECTED_FIRST_SITTING_START);

        JsonNode firstCase = firstSitting.path("hearing").path(0).path("case").path(0);
        assertThat(firstCase.path("caseUrn").asText()).isEqualTo("EK121443449");

        String contentDate = firstHeader(cathRequest, "x-content-date");
        assertThat(contentDate)
                .withFailMessage("x-content-date missing from WireMock request. Request=%s", cathRequest)
                .isEqualTo(EXPECTED_CONTENT_DATE_HEADER);
    }

    private JsonNode waitForAdditionalCaTHRequest(int previousCount, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> requests = listCaTHPublicationRequests();
            if (requests.size() > previousCount) {
                return requests.getLast();
            }
            Thread.sleep(WIREMOCK_POLL_MS);
        }
        List<JsonNode> requests = listCaTHPublicationRequests();
        throw new AssertionError("No new CaTH POST after publish. was=" + previousCount + ", now=" + requests.size());
    }

    private List<JsonNode> listCaTHPublicationRequests() throws Exception {
        ResponseEntity<String> admin = http.getForEntity(WIREMOCK_ADMIN_REQUESTS, String.class);
        assertThat(admin.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = objectMapper.readTree(admin.getBody());
        JsonNode requests = root.get("requests");
        if (requests == null || !requests.isArray()) {
            if (root.isArray()) {
                requests = root;
            } else {
                throw new AssertionError("Unexpected WireMock __admin/requests JSON: " + root);
            }
        }

        List<JsonNode> requestsOut = new ArrayList<>();
        for (JsonNode entry : requests) {
            JsonNode req = entry.has("request") ? entry.get("request") : entry;
            if (!"POST".equalsIgnoreCase(req.path("method").asText(""))) {
                continue;
            }
            String url = req.has("url") ? req.get("url").asText("") : req.path("absoluteUrl").asText("");
            if (!url.contains(CATH_PUBLICATION_URL_PATH)) {
                continue;
            }
            String body = requestBodyAsString(req);
            if (body != null && !body.isBlank()) {
                requestsOut.add(req);
            }
        }
        return requestsOut;
    }

    private static String firstHeader(JsonNode req, String headerName) {
        JsonNode headers = req.path("headers");
        if (!headers.isObject()) {
            return null;
        }

        JsonNode direct = headers.get(headerName);
        if (direct != null) {
            String parsed = parseHeaderNodeValue(direct);
            if (parsed != null && !parsed.isBlank()) {
                return parsed;
            }
        }

        Iterator<Map.Entry<String, JsonNode>> it = headers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(headerName)) {
                return parseHeaderNodeValue(e.getValue());
            }
        }
        return null;
    }

    private static String parseHeaderNodeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText(null);
        }
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).asText(null);
        }
        if (node.isObject()) {
            JsonNode values = node.get("values");
            if (values != null && values.isArray() && !values.isEmpty()) {
                return values.get(0).asText(null);
            }
            JsonNode value = node.get("value");
            if (value != null && !value.isNull()) {
                return value.asText(null);
            }
        }
        return node.asText(null);
    }

    private static String requestBodyAsString(JsonNode req) {
        if (req.has("body") && !req.get("body").isNull()) {
            String text = req.get("body").asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        if (req.has("bodyAsBase64") && !req.get("bodyAsBase64").isNull()) {
            String b64 = req.get("bodyAsBase64").asText("");
            if (!b64.isBlank()) {
                return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void forEachParty(JsonNode document, Consumer<JsonNode> consumer) {
        JsonNode courtLists = document.path("courtLists");
        if (!courtLists.isArray()) {
            return;
        }
        for (JsonNode courtList : courtLists) {
            JsonNode courtRooms = courtList.path("courtHouse").path("courtRoom");
            if (!courtRooms.isArray()) {
                continue;
            }
            for (JsonNode courtRoom : courtRooms) {
                JsonNode sessions = courtRoom.path("session");
                if (!sessions.isArray()) {
                    continue;
                }
                for (JsonNode session : sessions) {
                    JsonNode sittings = session.path("sittings");
                    if (!sittings.isArray()) {
                        continue;
                    }
                    for (JsonNode sitting : sittings) {
                        JsonNode hearings = sitting.path("hearing");
                        if (!hearings.isArray()) {
                            continue;
                        }
                        for (JsonNode hearing : hearings) {
                            JsonNode cases = hearing.path("case");
                            if (!cases.isArray()) {
                                continue;
                            }
                            for (JsonNode c : cases) {
                                JsonNode parties = c.path("party");
                                if (!parties.isArray()) {
                                    continue;
                                }
                                for (JsonNode party : parties) {
                                    consumer.accept(party);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static Optional<String> prosecutingAuthorityOrganisationName(JsonNode document) {
        final String[] found = {null};
        forEachParty(document, party -> {
            if (found[0] != null) {
                return;
            }
            if (!"PROSECUTING_AUTHORITY".equals(party.path("partyRole").asText())) {
                return;
            }
            String name = party.path("organisationDetails").path("organisationName").asText(null);
            if (name != null && !name.isBlank()) {
                found[0] = name;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    private static Optional<String> firstDefendantForenames(JsonNode document) {
        final String[] found = {null};
        forEachParty(document, party -> {
            if (found[0] != null) {
                return;
            }
            if (!"DEFENDANT".equals(party.path("partyRole").asText())) {
                return;
            }
            String forenames = party.path("individualDetails").path("individualForenames").asText(null);
            if (forenames != null && !forenames.isBlank()) {
                found[0] = forenames;
            }
        });
        return Optional.ofNullable(found[0]);
    }
}
