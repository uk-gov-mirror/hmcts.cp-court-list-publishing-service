package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.openapi.model.CourtListType.ONLINE_PUBLIC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Asserts the CaTH POST body captured by WireMock after an ONLINE_PUBLIC publish completes. */
public class CaTHPublishingIntegrationTest extends CourtListIntegrationTestBase {

    @Test
    void publishOnlinePublic_shouldPostTransformedCourtListDocumentToCaTH_withExpectedPartiesAndMetadata() throws Exception {
        UUID courtCentreId = UUID.randomUUID();
        int cathPostsBefore = listCaTHPublicationBodies().size();

        ResponseEntity<String> publishResponse = postPublishRequest(createPublishRequestJson(courtCentreId, ONLINE_PUBLIC.toString()));
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID courtListId = UUID.fromString(parseResponse(publishResponse).get("courtListId").asText());
        waitForTaskCompletion(courtListId, TASK_TIMEOUT_MS);

        JsonNode cathPayload = objectMapper.readTree(waitForAdditionalCaTHBody(cathPostsBefore, CATH_BODY_WAIT_MS));

        assertThat(cathPayload.path("document").path("publicationDate").asText()).isNotBlank();
        assertThat(prosecutingAuthorityOrganisationName(cathPayload)).contains("CITYPF");
        assertThat(firstDefendantForenames(cathPayload)).contains("Tommie");

        JsonNode firstRoom = cathPayload.path("courtLists").path(0).path("courtHouse").path("courtRoom").path(0);
        assertThat(firstRoom.path("courtRoomName").asText()).isEqualTo("Courtroom 01");

        JsonNode firstCase = firstRoom.path("session").path(0).path("sittings").path(0).path("hearing").path(0).path("case").path(0);
        assertThat(firstCase.path("caseUrn").asText()).isEqualTo("EK121443449");
    }

    private String waitForAdditionalCaTHBody(int previousCount, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> bodies = listCaTHPublicationBodies();
            if (bodies.size() > previousCount) {
                return bodies.getLast();
            }
            Thread.sleep(WIREMOCK_POLL_MS);
        }
        List<String> bodies = listCaTHPublicationBodies();
        throw new AssertionError("No new CaTH POST after publish. was=" + previousCount + ", now=" + bodies.size());
    }

    private List<String> listCaTHPublicationBodies() throws Exception {
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

        List<String> bodies = new ArrayList<>();
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
                bodies.add(body);
            }
        }
        return bodies;
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
