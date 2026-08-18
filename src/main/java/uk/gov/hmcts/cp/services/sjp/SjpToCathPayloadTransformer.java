package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.domain.sjp.cath.PubhubMaster;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathAddress;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathCases;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathCourtHouse;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathCourtLists;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathCourtRoom;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathDocument;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathHearing;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathIndividualDetails;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathOffence;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathOrganisationAddress;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathOrganisationDetails;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathParty;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathSession;
import uk.gov.hmcts.cp.domain.sjp.cath.SjpCathSittings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Transforms SJP list payload to CaTH/PubHub-style JSON using strongly-typed domain models
 * (equivalent to SjpPublishingHubTransformer in staging PubHub).
 */
@Component
public class SjpToCathPayloadTransformer {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();
    private static final String VERSION = "1.0";

    private static final String PROSECUTOR_NAME = "prosecutorName";
    private static final String LEGAL_ENTITY_NAME = "legalEntityName";
    private static final String DEFENDANT_NAME = "defendantName";
    private static final String FIRST_NAME = "firstName";
    private static final String LAST_NAME = "lastName";
    private static final String DATE_OF_BIRTH = "dateOfBirth";
    private static final String AGE = "age";
    private static final String ADDRESS_LINE_1 = "addressLine1";
    private static final String ADDRESS_LINE_2 = "addressLine2";
    private static final String ADDRESS_LINE_3 = "addressLine3";
    private static final String TOWN = "town";
    private static final String COUNTRY = "country";
    private static final String POSTCODE = "postcode";
    private static final String TITLE = "title";
    private static final String CASE_URN = "caseUrn";
    private static final String OFFENCE_TITLE = "title";
    private static final String OFFENCE_WORDING = "wording";
    private static final String REPORTING_RESTRICTION = "reportingRestriction";

    /**
     * Builds the CaTH payload (PubhubMaster) from the SJP list payload and serialises to JSON.
     *
     * @param listPayload  SJP list payload (generatedDateAndTime, readyCases)
     * @param documentName e.g. "SJP Public list" or "SJP Press list"
     * @return JSON string to send to CaTH via CourtListPublisher
     */
    public String transform(SjpListPayload listPayload, String documentName) throws JsonProcessingException {
        PubhubMaster master = buildPubhubMaster(listPayload, documentName);
        return OBJECT_MAPPER.writeValueAsString(master);
    }

    /**
     * Builds the strongly-typed PubhubMaster for testing or reuse.
     */
    public PubhubMaster buildPubhubMaster(SjpListPayload listPayload, String documentName) {
        String publicationDate = toUtcIso(listPayload.getGeneratedDateAndTime());

        SjpCathDocument document = SjpCathDocument.builder()
                .documentName(documentName)
                .publicationDate(publicationDate)
                .version(VERSION)
                .build();

        SjpCathCourtLists courtLists = SjpCathCourtLists.builder()
                .courtHouse(buildCourtHouse(listPayload.getReadyCases(), documentName))
                .build();

        return PubhubMaster.builder()
                .document(document)
                .courtLists(List.of(courtLists))
                .build();
    }

    private SjpCathCourtHouse buildCourtHouse(List<Map<String, Object>> readyCases, String documentName) {
        if (readyCases == null || readyCases.isEmpty()) {
            return SjpCathCourtHouse.builder()
                    .courtRoom(List.of(buildEmptyCourtRoom()))
                    .build();
        }

        List<SjpCathHearing> hearings = readyCases.stream()
                .map(rc -> buildHearing(rc, documentName))
                .collect(Collectors.toList());

        SjpCathSittings sittings = SjpCathSittings.builder()
                .hearing(hearings)
                .build();

        SjpCathSession session = SjpCathSession.builder()
                .sittings(List.of(sittings))
                .build();

        SjpCathCourtRoom courtRoom = SjpCathCourtRoom.builder()
                .session(List.of(session))
                .build();

        return SjpCathCourtHouse.builder()
                .courtRoom(List.of(courtRoom))
                .build();
    }

    private SjpCathCourtRoom buildEmptyCourtRoom() {
        return SjpCathCourtRoom.builder()
                .session(List.of(SjpCathSession.builder()
                        .sittings(List.of(SjpCathSittings.builder()
                                .hearing(Collections.emptyList())
                                .build()))
                        .build()))
                .build();
    }

    private SjpCathHearing buildHearing(Map<String, Object> readyCase, String documentName) {
        List<SjpCathParty> parties = buildParties(readyCase);
        List<SjpCathOffence> offences = buildOffences(readyCase, documentName);
        List<SjpCathCases> cases = buildCases(readyCase);

        return SjpCathHearing.builder()
                .party(parties)
                .offence(offences)
                .cases(cases)
                .build();
    }

    private List<SjpCathParty> buildParties(Map<String, Object> readyCase) {
        List<SjpCathParty> parties = new ArrayList<>();

        getString(readyCase, PROSECUTOR_NAME).ifPresent(name -> parties.add(
                SjpCathParty.builder()
                        .partyRole("PROSECUTOR")
                        .organisationDetails(SjpCathOrganisationDetails.builder()
                                .organisationName(name)
                                .build())
                        .build()));

        getString(readyCase, LEGAL_ENTITY_NAME).ifPresent(name -> parties.add(
                SjpCathParty.builder()
                        .partyRole("ACCUSED")
                        .organisationDetails(buildOrganisationDetails(readyCase, name))
                        .build()));

        getString(readyCase, DEFENDANT_NAME).ifPresent(defendantName -> {
            if (!defendantName.isEmpty()) {
                parties.add(SjpCathParty.builder()
                        .partyRole("ACCUSED")
                        .individualDetails(buildIndividualDetails(readyCase))
                        .build());
            }
        });

        return parties;
    }

    private SjpCathIndividualDetails buildIndividualDetails(Map<String, Object> readyCase) {
        List<String> addressLines = new ArrayList<>();
        getString(readyCase, ADDRESS_LINE_1).ifPresent(addressLines::add);
        getString(readyCase, ADDRESS_LINE_2).ifPresent(addressLines::add);
        getString(readyCase, ADDRESS_LINE_3).ifPresent(addressLines::add);

        SjpCathAddress address = SjpCathAddress.builder()
                .line(addressLines.isEmpty() ? null : addressLines)
                .town(getString(readyCase, TOWN).orElse(null))
                .county(getString(readyCase, COUNTRY).orElse(null))
                .postCode(getString(readyCase, POSTCODE).orElse(null))
                .build();

        return SjpCathIndividualDetails.builder()
                .title(getString(readyCase, TITLE).orElse(null))
                .individualForenames(getString(readyCase, FIRST_NAME).orElse(null))
                .individualSurname(getString(readyCase, LAST_NAME).orElse(null))
                .dateOfBirth(getString(readyCase, DATE_OF_BIRTH).orElse(null))
                .age(getInteger(readyCase, AGE).orElse(null))
                .address(address)
                .build();
    }

    private SjpCathOrganisationDetails buildOrganisationDetails(Map<String, Object> readyCase, String organisationName) {
        List<String> addressLines = new ArrayList<>();
        getString(readyCase, ADDRESS_LINE_1).ifPresent(addressLines::add);
        getString(readyCase, ADDRESS_LINE_2).ifPresent(addressLines::add);
        getString(readyCase, ADDRESS_LINE_3).ifPresent(addressLines::add);

        SjpCathOrganisationAddress orgAddress = SjpCathOrganisationAddress.builder()
                .line(addressLines.isEmpty() ? null : addressLines)
                .town(getString(readyCase, TOWN).orElse(null))
                .county(getString(readyCase, COUNTRY).orElse(null))
                .postCode(getString(readyCase, POSTCODE).orElse(null))
                .build();

        return SjpCathOrganisationDetails.builder()
                .organisationName(organisationName)
                .organisationAddress(orgAddress)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<SjpCathOffence> buildOffences(Map<String, Object> readyCase, String documentName) {
        Object raw = readyCase.get("sjpOffences");
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }

        boolean isPressList = SjpDocumentType.SJP_PRESS_LIST.getValue().equals(documentName);
        List<SjpCathOffence> offences = new ArrayList<>();

        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> objectMap = (Map<String, Object>) item;
            // Press schema requires reportingRestriction on every offence, so default to false (not restricted) when the source data doesn't supply it — omitting it entirely
            // (as a null would, via @JsonInclude(NON_NULL) on SjpCathOffence) fails schema validation. Public list schema doesn't require it, so leave it unset there.
            Boolean reportingRestriction = isPressList
                    ? Boolean.TRUE.equals(objectMap.get(REPORTING_RESTRICTION))
                    : null;

            offences.add(SjpCathOffence.builder()
                    .offenceTitle(getString(objectMap, OFFENCE_TITLE).orElse(null))
                    .offenceWording(getString(objectMap, OFFENCE_WORDING).orElse(null))
                    .reportingRestriction(reportingRestriction)
                    .build());
        }
        return offences;
    }

    private List<SjpCathCases> buildCases(Map<String, Object> readyCase) {
        return getString(readyCase, CASE_URN)
                .filter(urn -> !urn.isEmpty())
                .map(urn -> List.of(SjpCathCases.builder().caseUrn(urn).build()))
                .orElse(Collections.emptyList());
    }

    private static Optional<String> getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    private static Optional<Integer> getInteger(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Optional.empty();
        }
        if (v instanceof Number) {
            return Optional.of(((Number) v).intValue());
        }
        try {
            return Optional.of(Integer.parseInt(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Ensures the date-time string ends with 'Z' (UTC) as required by CaTH publicationDate regex:
     * {@code ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}([.]\d{1,9})?Z$}
     */
    static String toUtcIso(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) {
            return "";
        }
        String trimmed = dateTime.trim();
        return trimmed.endsWith("Z") ? trimmed : trimmed + "Z";
    }
}
