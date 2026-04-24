package uk.gov.hmcts.cp.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import uk.gov.hmcts.cp.models.CourtApplication;
import uk.gov.hmcts.cp.models.CourtApplicationParty;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.models.Defendant;
import uk.gov.hmcts.cp.models.Hearing;
import uk.gov.hmcts.cp.models.ReportingRestriction;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.models.transformed.schema.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
class OnlinePublicCourtListTransformationServiceTest {

    private OnlinePublicCourtListTransformationService transformationService;
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();
    private CourtListPayload payload;

    @BeforeEach
    void setUp() throws Exception {
        // Create transformation service
        transformationService = new OnlinePublicCourtListTransformationService();
        
        payload = loadPayloadFromStubData("stubdata/court-list-payload-public.json");
    }

    @Test
    void transform_shouldTransformToSimplifiedFormat() throws Exception {
        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then - Verify document structure matches new schema
        assertThat(document).isNotNull();
        assertThat(document.getDocument()).isNotNull();
        
        // Verify DocumentSchema with publicationDate
        DocumentSchema documentSchema = document.getDocument();
        assertThat(documentSchema.getPublicationDate()).isNotNull();
        assertThat(documentSchema.getPublicationDate()).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}([.]\\d{1,3})?Z$");
        
        // Verify Venue
        Venue venue = document.getVenue();
        assertThat(venue).isNotNull();
        assertThat(venue.getVenueAddress()).isNotNull();
        
        // Verify CourtLists
        List<CourtList> courtLists = document.getCourtLists();
        assertThat(courtLists).isNotNull();
        assertThat(courtLists).isNotEmpty();
        
        // Verify structure
        CourtHouse courtHouse = courtLists.getFirst().getCourtHouse();
        assertThat(courtHouse).isNotNull();
        assertThat(courtHouse.getCourtRoom()).isNotEmpty();
        
        CourtRoomSchema courtRoom = courtHouse.getCourtRoom().getFirst();
        assertThat(courtRoom).isNotNull();
        assertThat(courtRoom.getSession()).isNotEmpty();
        
        SessionSchema session = courtRoom.getSession().getFirst();
        assertThat(session).isNotNull();
        assertThat(session.getSittings()).isNotEmpty();
        
        Sitting sitting = session.getSittings().getFirst();
        assertThat(sitting).isNotNull();
        assertThat(sitting.getHearing()).isNotEmpty();
        
        HearingSchema hearing = sitting.getHearing().getFirst();
        assertThat(hearing).isNotNull();
        assertThat(hearing.getCaseList()).isNotEmpty();
        // Online public schema: no panel; channel and application are arrays (empty when no source data)
        assertThat(hearing.getPanel()).isNull();
        assertThat(hearing.getChannel()).isNotNull();
        assertThat(hearing.getChannel()).isEmpty();
        assertThat(hearing.getApplication()).isNotNull();
        assertThat(hearing.getApplication()).isEmpty();

        // For public lists, verify simplified case structure
        CaseSchema caseObj = hearing.getCaseList().getFirst();
        assertThat(caseObj).isNotNull();
        assertThat(caseObj.getCaseUrn()).isNotNull();
        assertThat(caseObj.getParty()).isNotEmpty();
        // Online public schema has no caseSequenceIndicator
        assertThat(caseObj.getCaseSequenceIndicator()).isNull();
        
        // Verify party has minimal information (name only for public lists)
        Party party = caseObj.getParty().getFirst();
        assertThat(party).isNotNull();
        assertThat(party.getPartyRole()).isEqualTo("DEFENDANT");
        assertThat(party.getIndividualDetails()).isNotNull();
        
        IndividualDetails individualDetails = party.getIndividualDetails();
        assertThat(individualDetails.getIndividualForenames()).isNotNull();
        assertThat(individualDetails.getIndividualSurname()).isNotNull();
        // Public lists should not include sensitive information like DOB, address, etc.
        assertThat(individualDetails.getDateOfBirth()).isNull();
        assertThat(individualDetails.getAddress()).isNull();
        // Stub defendant has offences; public list includes offenceTitle per schema
        assertThat(party.getOffence()).isNotEmpty();
        assertThat(party.getOffence().getFirst().getOffenceTitle()).isEqualTo("Attempt theft of motor vehicle");
        // Stub has no court application, so subject is false
        assertThat(party.getSubject()).isFalse();

        // Verify second Party (PROSECUTING_AUTHORITY) from prosecutorType in stub
        assertThat(caseObj.getParty()).hasSize(2);
        Party prosecutorParty = caseObj.getParty().get(1);
        assertThat(prosecutorParty).isNotNull();
        assertThat(prosecutorParty.getPartyRole()).isEqualTo("PROSECUTING_AUTHORITY");
        assertThat(prosecutorParty.getOrganisationDetails()).isNotNull();
        assertThat(prosecutorParty.getOrganisationDetails().getOrganisationName()).isEqualTo("CITYPF");
    }

    @Test
    void transform_shouldMapDefendantOrganisationNameToPartyOrganisationDetailsForCaTH() throws Exception {
        Defendant defendant = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst().getDefendants().getFirst();
        defendant.setOrganisationName("OrganisationName0");

        CourtListDocument document = transformationService.transform(payload);

        Party defendantParty = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst()
                .getParty().getFirst();
        assertThat(defendantParty.getPartyRole()).isEqualTo("DEFENDANT");
        assertThat(defendantParty.getOrganisationDetails()).isNotNull();
        assertThat(defendantParty.getOrganisationDetails().getOrganisationName()).isEqualTo("OrganisationName0");
        assertThat(defendantParty.getIndividualDetails()).isNotNull();
        assertThat(defendantParty.getIndividualDetails().getIndividualForenames()).isEqualTo("Tommie");
    }

    @Test
    void transform_shouldMapOrganisationOnlyDefendantToOrganisationDetailsWithoutIndividualDetails() throws Exception {
        Defendant defendant = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst().getDefendants().getFirst();
        defendant.setFirstName(null);
        defendant.setSurname(null);
        defendant.setOrganisationName("Acme Corp Ltd");

        CourtListDocument document = transformationService.transform(payload);

        Party defendantParty = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst()
                .getParty().getFirst();
        assertThat(defendantParty.getIndividualDetails()).isNull();
        assertThat(defendantParty.getOrganisationDetails()).isNotNull();
        assertThat(defendantParty.getOrganisationDetails().getOrganisationName()).isEqualTo("Acme Corp Ltd");
    }

    @Test
    void transform_shouldCopyReferenceDataFieldsFromPayloadToDocument() throws Exception {
        // Given - payload enriched with ouCode/courtId from getCourtCenterDataByCourtName
        payload.setOuCode("B01LY00");
        payload.setCourtId("f8254db1-1683-483e-afb3-b87fde5a0a26");
        payload.setCourtIdNumeric("325");

        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then - reference data fields are present on document
        assertThat(document).isNotNull();
    }

    @Test
    void transform_shouldIncludeVenueAddress() throws Exception {
        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then - schema requires venueAddress.line and venueAddress.postCode
        assertThat(document).isNotNull();
        Venue venue = document.getVenue();
        assertThat(venue).isNotNull();
        AddressSchema venueAddress = venue.getVenueAddress();
        assertThat(venueAddress).isNotNull();
        assertThat(venueAddress.getLine()).isNotNull();
        assertThat(venueAddress.getPostCode()).isNotNull(); // required by schema, never null
    }

    @Test
    void transform_shouldUseAddress1AndAddress2FromPayload() throws Exception {
        // Payload has address1, address2 (e.g. from enrichment or court centre fallback)
        payload.setAddress1("176A Lavender Hill London");
        payload.setAddress2("SW11 1JU");

        CourtListDocument document = transformationService.transform(payload);

        AddressSchema venueAddress = document.getVenue().getVenueAddress();
        assertThat(venueAddress.getLine()).hasSize(2);
        assertThat(venueAddress.getLine().get(0)).isEqualTo("176A Lavender Hill London");
        assertThat(venueAddress.getLine().get(1)).isEqualTo("SW11 1JU");
        assertThat(venueAddress.getPostCode()).isEmpty();
    }

    @Test
    void transform_shouldUseAddress1Address2AndPostcodeFromPayload() throws Exception {
        payload.setAddress1("176A Lavender Hill London");
        payload.setAddress2("SW11 1JU");
        payload.setPostcode("SW11 1JU");

        CourtListDocument document = transformationService.transform(payload);

        AddressSchema venueAddress = document.getVenue().getVenueAddress();
        assertThat(venueAddress.getLine()).hasSize(2);
        assertThat(venueAddress.getLine().get(0)).isEqualTo("176A Lavender Hill London");
        assertThat(venueAddress.getLine().get(1)).isEqualTo("SW11 1JU");
        assertThat(venueAddress.getPostCode()).isEqualTo("SW11 1JU");
    }

    @Test
    void transform_shouldUseVenueAddressFromReferenceDataWhenPresent() throws Exception {
        // Given – payload enriched with reference data (address1-5, postcode)
        payload.setAddress1("176A Lavender Hill");
        payload.setAddress2("London");
        payload.setPostcode("SW11 1JU");

        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then – venue uses full address and postcode from reference data
        AddressSchema venueAddress = document.getVenue().getVenueAddress();
        assertThat(venueAddress.getLine()).hasSize(2);
        assertThat(venueAddress.getLine().get(0)).isEqualTo("176A Lavender Hill");
        assertThat(venueAddress.getLine().get(1)).isEqualTo("London");
        assertThat(venueAddress.getPostCode()).isEqualTo("SW11 1JU");
    }

    @Test
    void transform_shouldHaveCorrectPublicationDate() throws Exception {
        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then
        assertThat(document).isNotNull();
        DocumentSchema documentSchema = document.getDocument();
        assertThat(documentSchema.getPublicationDate()).isNotNull();
        // Verify it's a valid ISO 8601 date-time string
        assertThat(documentSchema.getPublicationDate()).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}([.]\\d{1,3})?Z$");
    }

    @Test
    void transform_shouldDeserializeDefendantAsnFromPayload() throws Exception {
        // Stub court-list-payload-public.json has first defendant with "asn": "REF456"
        Defendant firstDefendant = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst().getDefendants().getFirst();
        assertThat(firstDefendant.getAsn()).isEqualTo("REF456");
    }

    @Test
    void transform_shouldAddProsecutorPartyWhenProsecutorTypeIsSet() throws Exception {
        // Stub court-list-payload-public.json has prosecutorType "CITYPF" on first hearing
        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();

        assertThat(caseObj.getParty()).hasSize(2);
        assertThat(caseObj.getParty().get(0).getPartyRole()).isEqualTo("DEFENDANT");
        Party prosecutorParty = caseObj.getParty().get(1);
        assertThat(prosecutorParty.getPartyRole()).isEqualTo("PROSECUTING_AUTHORITY");
        assertThat(prosecutorParty.getOrganisationDetails().getOrganisationName()).isEqualTo("CITYPF");
        assertThat(prosecutorParty.getIndividualDetails()).isNull();
        assertThat(prosecutorParty.getOffence()).isNull();
    }

    @Test
    void transform_shouldNotAddProsecutorPartyWhenProsecutorTypeIsNull() throws Exception {
        // Clear prosecutorType from first hearing
        payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst().setProsecutorType(null);

        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();

        assertThat(caseObj.getParty()).hasSize(1);
        assertThat(caseObj.getParty().getFirst().getPartyRole()).isEqualTo("DEFENDANT");
    }

    @Test
    void transform_shouldNotAddProsecutorPartyWhenProsecutorTypeIsBlank() throws Exception {
        payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst().setProsecutorType("   ");

        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();

        assertThat(caseObj.getParty()).hasSize(1);
        assertThat(caseObj.getParty().getFirst().getPartyRole()).isEqualTo("DEFENDANT");
    }

    @Test
    void transform_shouldSetSubjectTrueWhenCourtApplicationSubjectMatchesDefendant() throws Exception {
        CourtApplicationParty subjectParty = CourtApplicationParty.builder()
                .id("1b4ffa2a-aeb4-4d03-993e-b40e2e404c7d")
                .build();
        payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst()
                .setCourtApplication(CourtApplication.builder()
                        .subject(subjectParty)
                        .build());

        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();

        Party defendantParty = caseObj.getParty().getFirst();
        assertThat(defendantParty.getPartyRole()).isEqualTo("DEFENDANT");
        assertThat(defendantParty.getSubject()).isTrue();
    }

    @Test
    void transform_shouldSetSubjectFalseWhenCourtApplicationHasNoMatchingSubject() throws Exception {
        CourtApplicationParty otherSubject = CourtApplicationParty.builder()
                .id("different-id-not-matching-defendant")
                .build();
        payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst()
                .setCourtApplication(CourtApplication.builder()
                        .subject(otherSubject)
                        .build());

        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();

        assertThat(caseObj.getParty().getFirst().getSubject()).isFalse();
    }

    @Test
    void transform_shouldSetSubjectFalseWhenNoCourtApplication() throws Exception {
        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();

        assertThat(caseObj.getParty().getFirst().getSubject()).isFalse();
    }

    @Test
    void transform_shouldSetReportingRestrictionFromDefendantReportingRestrictionsArray() throws Exception {
        // Given - defendant with reportingRestrictions array
        Hearing hearing = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst();
        Defendant defendant = hearing.getDefendants().getFirst();
        defendant.setReportingRestrictions(List.of(
                ReportingRestriction.builder().label("Section 49 of the Children and Young Persons Act 1933 applies").build()
        ));

        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then - case level
        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();
        assertThat(caseObj.getReportingRestriction()).isTrue();
        assertThat(caseObj.getReportingRestrictionDetails())
                .containsExactly("Section 49 of the Children and Young Persons Act 1933 applies");

        // Then - offence level
        List<OffenceSchema> offences = caseObj.getParty().getFirst().getOffence();
        assertThat(offences).isNotEmpty();
        OffenceSchema offence = offences.getFirst();
        assertThat(offence.getReportingRestriction()).isTrue();
        assertThat(offence.getReportingRestrictionDetails())
                .containsExactly("Section 49 of the Children and Young Persons Act 1933 applies");
    }

    @Test
    void transform_shouldSetNoReportingRestrictionWhenDefendantHasNoReportingRestrictions() throws Exception {
        Hearing hearing = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst();
        hearing.getDefendants().getFirst().setReportingRestrictions(Collections.emptyList());

        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();
        assertThat(caseObj.getReportingRestriction()).isFalse();
        assertThat(caseObj.getReportingRestrictionDetails()).isNull();

        OffenceSchema offence = caseObj.getParty().getFirst().getOffence().getFirst();
        assertThat(offence.getReportingRestriction()).isNull();
        assertThat(offence.getReportingRestrictionDetails()).isNull();
    }

    @Test
    void transform_shouldSetNoReportingRestrictionWhenDefendantReportingRestrictionsIsNull() throws Exception {
        Hearing hearing = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst();
        hearing.getDefendants().getFirst().setReportingRestrictions(null);

        CourtListDocument document = transformationService.transform(payload);

        CaseSchema caseObj = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst().getCaseList().getFirst();
        assertThat(caseObj.getReportingRestriction()).isFalse();
        assertThat(caseObj.getReportingRestrictionDetails()).isNull();
    }

    @Test
    void transform_shouldAddSubjectFromParentHearingToApplicationPartiesWithIsSubjectTrue() {
        // Given - application on hearing with no subject on courtApplication; subject is on parent (hearing)
        Hearing hearing = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst();
        CourtApplicationParty parentSubject = CourtApplicationParty.builder()
                .id("subject-from-parent-id")
                .firstName("John")
                .surname("Smith")
                .build();
        hearing.setCourtApplicationId("PUBLIC-APP-REF-99");
        hearing.setCourtApplication(CourtApplication.builder()
                .applicant(CourtApplicationParty.builder()
                        .name("Applicant Name")
                        .dateOfBirth("1 Jan 1990")
                        .build())
                .respondents(List.of(
                        CourtApplicationParty.builder()
                                .name("Respondent One")
                                .build()
                ))
                .build());
        hearing.setSubject(parentSubject);

        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then - application has applicant, respondent, and subject (from parent); subject party has isSubject=true
        HearingSchema hearingSchema = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst();
        assertThat(hearingSchema.getCaseList()).isEmpty();
        List<Application> applications = hearingSchema.getApplication();
        assertThat(applications).hasSize(1);
        Application app = applications.getFirst();
        assertThat(app.getParty()).hasSize(3); // applicant + respondent + subject from parent
        Party subjectParty = app.getParty().stream()
                .filter(p -> "SUBJECT".equals(p.getPartyRole()))
                .findFirst()
                .orElseThrow();
        assertThat(subjectParty.getSubject()).isTrue();
        assertThat(subjectParty.getIndividualDetails()).isNotNull();
        assertThat(subjectParty.getIndividualDetails().getIndividualForenames()).isEqualTo("John");
        assertThat(subjectParty.getIndividualDetails().getIndividualSurname()).isEqualTo("Smith");
    }

    @Test
    void transform_shouldIncludeApplicationsWhenHearingHasCourtApplication() throws Exception {
        // Given - hearing with courtApplicationId and courtApplication (applicant + respondents)
        Hearing hearing = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst();
        hearing.setCourtApplicationId("PUBLIC-APP-REF-99");
        hearing.setCourtApplication(CourtApplication.builder()
                .applicant(CourtApplicationParty.builder()
                        .name("Applicant Name")
                        .dateOfBirth("1 Jan 1990")
                        .build())
                .respondents(List.of(
                        CourtApplicationParty.builder()
                                .name("Respondent One")
                                .build()
                ))
                .build());

        // When
        CourtListDocument document = transformationService.transform(payload);

        // Then - first hearing has one application and no cases (when application exists, cases are excluded); public list uses minimal party details (name only)
        HearingSchema hearingSchema = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst();
        assertThat(hearingSchema.getCaseList()).isEmpty();
        List<Application> applications = hearingSchema.getApplication();
        assertThat(applications).hasSize(1);
        Application app = applications.getFirst();
        assertThat(app.getApplicationReference()).isEqualTo(hearing.getCaseNumber());
        assertThat(app.getApplicationType()).isNull();
        assertThat(app.getParty()).hasSize(2); // applicant + one respondent
        assertThat(app.getParty().getFirst().getPartyRole()).isEqualTo("APPLICANT");
        assertThat(app.getParty().getFirst().getIndividualDetails().getIndividualSurname()).isEqualTo("Applicant Name");
        assertThat(app.getParty().getFirst().getIndividualDetails().getDateOfBirth()).isEqualTo("1990-01-01"); // public list: no DOB
        assertThat(app.getParty().get(1).getPartyRole()).isEqualTo("RESPONDENT");
        assertThat(app.getParty().get(1).getIndividualDetails().getIndividualSurname()).isEqualTo("Respondent One");
    }

    @Test
    void transform_shouldStripSurroundingWhitespaceFromApplicationParticularsViaCaTHStringUtils() {
        Hearing hearing = payload.getHearingDates().getFirst().getCourtRooms().getFirst()
                .getTimeslots().getFirst().getHearings().getFirst();
        hearing.setCourtApplicationId("PUBLIC-APP-REF-99");
        hearing.setCourtApplication(CourtApplication.builder()
                .applicationParticulars("\n Some application particulars\n ")
                .applicant(CourtApplicationParty.builder()
                        .name("Applicant Name")
                        .build())
                .build());

        CourtListDocument document = transformationService.transform(payload);

        Application app = document.getCourtLists().getFirst().getCourtHouse().getCourtRoom().getFirst()
                .getSession().getFirst().getSittings().getFirst().getHearing().getFirst()
                .getApplication().getFirst();
        assertThat(app.getApplicationParticulars()).isEqualTo("Some application particulars");
    }

    private CourtListPayload loadPayloadFromStubData(String resourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return objectMapper.readValue(json, CourtListPayload.class);
    }

}
