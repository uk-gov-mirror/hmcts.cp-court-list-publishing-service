package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.domain.sjp.cath.PubhubMaster;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SjpToCathPayloadTransformerTest {

    private SjpToCathPayloadTransformer transformer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        transformer = new SjpToCathPayloadTransformer();
        objectMapper = new ObjectMapper();
    }

    @Test
    void buildPubhubMaster_returnsStronglyTypedHierarchy() {
        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(
                        Map.<String, Object>of(
                                "caseUrn", "case-1",
                                "defendantName", "Defendant One",
                                "firstName", "Defendant",
                                "lastName", "One",
                                "prosecutorName", "CPS",
                                "sjpOffences", List.of(
                                        Map.of("title", "Offence 1", "wording", "Wording 1")
                                )
                        )
                )
        );

        PubhubMaster master = transformer.buildPubhubMaster(payload, SjpDocumentType.SJP_PUBLIC_LIST.getValue());

        assertThat(master.getDocument()).isNotNull();
        assertThat(master.getDocument().getDocumentName()).isEqualTo("SJP Public list");
        assertThat(master.getDocument().getPublicationDate()).isEqualTo("2025-03-09T10:00:00Z");
        assertThat(master.getDocument().getVersion()).isEqualTo("1.0");

        assertThat(master.getCourtLists()).hasSize(1);
        assertThat(master.getCourtLists().getFirst().getCourtHouse()).isNotNull();
        assertThat(master.getCourtLists().getFirst().getCourtHouse().getCourtRoom()).hasSize(1);

        var courtRoom = master.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst();
        assertThat(courtRoom.getSession()).hasSize(1);
        assertThat(courtRoom.getSession().getFirst().getSittings()).hasSize(1);

        var sittings = courtRoom.getSession().getFirst().getSittings().getFirst();
        assertThat(sittings.getHearing()).hasSize(1);

        var hearing = sittings.getHearing().getFirst();
        assertThat(hearing.getParty()).hasSize(2); // prosecutor + defendant
        assertThat(hearing.getOffence()).hasSize(1);
        assertThat(hearing.getOffence().getFirst().getOffenceTitle()).isEqualTo("Offence 1");
        assertThat(hearing.getCases()).hasSize(1);
        assertThat(hearing.getCases().getFirst().getCaseUrn()).isEqualTo("case-1");
    }

    @Test
    void transform_publicList_producesValidJsonWithExpectedStructure() throws Exception {
        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(Map.<String, Object>of("caseUrn", "urn-1", "defendantName", "Name"))
        );

        String json = transformer.transform(payload, SjpDocumentType.SJP_PUBLIC_LIST.getValue());

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.has("document")).isTrue();
        assertThat(root.get("document").get("documentName").asText()).isEqualTo("SJP Public list");
        assertThat(root.get("document").get("publicationDate").asText()).isEqualTo("2025-03-09T10:00:00Z");
        assertThat(root.has("courtLists")).isTrue();
        assertThat(root.get("courtLists").isArray()).isTrue();
        assertThat(root.get("courtLists").get(0).has("courtHouse")).isTrue();
        assertThat(root.get("courtLists").get(0).get("courtHouse").has("courtRoom")).isTrue();
    }

    @Test
    void transform_publicList_omitsReportingRestriction_whenSjpOffencesPresentWithoutIt() throws Exception {
        // Public list schema doesn't require reportingRestriction; it must stay absent even
        // when sjpOffences is present, regardless of whether the source data supplies the field.
        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(Map.<String, Object>of(
                        "caseUrn", "urn-public-offence-1",
                        "defendantName", "Name",
                        "sjpOffences", List.of(Map.of("title", "Speeding", "wording", "Drove too fast"))
                ))
        );

        String json = transformer.transform(payload, SjpDocumentType.SJP_PUBLIC_LIST.getValue());

        JsonNode offence = objectMapper.readTree(json).get("courtLists").get(0)
                .get("courtHouse").get("courtRoom").get(0)
                .get("session").get(0).get("sittings").get(0)
                .get("hearing").get(0).get("offence").get(0);
        assertThat(offence.get("offenceTitle").asText()).isEqualTo("Speeding");
        assertThat(offence.has("reportingRestriction")).isFalse();
    }

    @Test
    void transform_pressList_producesValidJsonWithExpectedStructure() throws Exception {
        // Triggered by public.sjp.pending-cases-press-list-generated (not the transparency report)
        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(Map.<String, Object>of(
                        "caseUrn", "urn-2",
                        "defendantName", "Name",
                        "sjpOffences", List.of(Map.of("title", "Speeding", "wording", "Drove too fast", "reportingRestriction", true))
                ))
        );

        String json = transformer.transform(payload, SjpDocumentType.SJP_PRESS_LIST.getValue());

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("document").get("documentName").asText()).isEqualTo("SJP Press list");
        assertThat(root.get("document").get("publicationDate").asText()).isEqualTo("2025-03-09T10:00:00Z");

        JsonNode offence = root.get("courtLists").get(0)
                .get("courtHouse").get("courtRoom").get(0)
                .get("session").get(0).get("sittings").get(0)
                .get("hearing").get(0).get("offence").get(0);
        assertThat(offence.get("offenceTitle").asText()).isEqualTo("Speeding");
        assertThat(offence.get("reportingRestriction").asBoolean()).isTrue();
    }

    @Test
    void transform_pressList_defaultsReportingRestrictionToFalse_whenSourceDataOmitsIt() throws Exception {
        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(Map.<String, Object>of(
                        "caseUrn", "urn-3",
                        "defendantName", "Name",
                        "sjpOffences", List.of(Map.of("title", "Speeding", "wording", "Drove too fast"))
                ))
        );

        String json = transformer.transform(payload, SjpDocumentType.SJP_PRESS_LIST.getValue());

        JsonNode offence = objectMapper.readTree(json).get("courtLists").get(0)
                .get("courtHouse").get("courtRoom").get(0)
                .get("session").get(0).get("sittings").get(0)
                .get("hearing").get(0).get("offence").get(0);
        assertThat(offence.has("reportingRestriction")).isTrue();
        assertThat(offence.get("reportingRestriction").asBoolean()).isFalse();
    }

    @Test
    void buildPubhubMaster_emptyReadyCases_buildsEmptyCourtRoom() {
        SjpListPayload payload = new SjpListPayload("2025-03-09T10:00:00", List.of());

        PubhubMaster master = transformer.buildPubhubMaster(payload, SjpDocumentType.SJP_PUBLIC_LIST.getValue());

        assertThat(master.getCourtLists()).hasSize(1);
        assertThat(master.getCourtLists().getFirst().getCourtHouse().getCourtRoom()).hasSize(1);
        var sittings = master.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst();
        assertThat(sittings.getHearing()).isEmpty();
    }
}
