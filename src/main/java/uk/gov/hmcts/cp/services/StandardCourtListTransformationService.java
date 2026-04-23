package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.models.*;
import uk.gov.hmcts.cp.models.transformed.schema.*;
import uk.gov.hmcts.cp.util.CaTHStringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StandardCourtListTransformationService extends BaseCourtListTransformationService {

    /** Abbreviated month: "5 Jan 2006", "20 Sept 1978". Locale.UK uses "Sept" for September. */
    private static final DateTimeFormatter DOB_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
    /** Full month name fallback: "11 September 1972". */
    private static final DateTimeFormatter DOB_FORMATTER_FULL_MONTH = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    /** Date-only pattern (YYYY-MM-DD) for normalising to ISO date-time for schema. */
    private static final Pattern DATE_ONLY_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @Override
    protected String getTransformLogMessage() {
        return "Transforming progression court list payload to document format";
    }

    @Override
    protected AddressSchema buildVenueAddressFromPayload(CourtListPayload payload) {
        if (isNonBlank(payload.getAddress1()) || isNonBlank(payload.getAddress2()) || isNonBlank(payload.getAddress3())
                || isNonBlank(payload.getAddress4()) || isNonBlank(payload.getAddress5()) || isNonBlank(payload.getPostcode())) {
            return transformAddressSchemaFromStrings(
                    payload.getAddress1(), payload.getAddress2(), payload.getAddress3(),
                    payload.getAddress4(), payload.getAddress5(), payload.getPostcode());
        }
        return transformAddressSchemaFromStrings(payload.getCourtCentreAddress1(), payload.getCourtCentreAddress2());
    }

    @Override
    protected CourtHouse buildCourtHouse(List<CourtRoomSchema> courtRooms, CourtListPayload payload) {
        String courtHouseName = payload.getCourtCentreName();
        String lja = isNonBlank(payload.getLjaName()) ? payload.getLjaName() : payload.getCourtCentreName();
        return CourtHouse.builder()
                .courtHouseName(courtHouseName)
                .lja(lja)
                .courtRoom(courtRooms)
                .build();
    }

    @Override
    protected HearingSchema transformHearing(Hearing hearing) {
        ApplicationTransformResult appResult = transformApplications(hearing);
        List<Application> applications = appResult.applications();
        boolean hasApplications = applications != null && !applications.isEmpty();

        List<CaseSchema> cases;
        if (hasApplications) {
            cases = Collections.emptyList();
        } else {
            cases = transformCases(hearing, appResult.subjectPartyId());
        }

        if (cases.isEmpty() && !hasApplications) {
            return null;
        }

        return HearingSchema.builder()
                .hearingType(hearing.getHearingType())
                .caseList(cases)
                .panel(hearing.getPanel())
                .channel(Collections.emptyList())
                .application(appResult.applications())
                .build();
    }

    /**
     * Transforms hearing court application data into schema Application list and subject party id.
     * Subject party id is derived here and used in buildApplicationParty (Party.subject) and in transformCases (defendant Party.subject).
     */
    @Override
    protected Party buildApplicationParty(CourtApplicationParty courtParty, String partyRole, String subjectPartyId) {
        if (courtParty == null) {
            return null;
        }
        IndividualDetails individualDetails = null;
        if (isNonBlank(courtParty.getFirstName()) || isNonBlank(courtParty.getSurname()) || isNonBlank(courtParty.getName()) || isNonBlank(courtParty.getDateOfBirth())) {
            String surname = isNonBlank(courtParty.getSurname()) ? courtParty.getSurname() : courtParty.getName();
            individualDetails = IndividualDetails.builder()
                    .individualForenames(courtParty.getFirstName())
                    .individualMiddleName(null)
                    .individualSurname(surname)
                    .dateOfBirth(convertDateOfBirthToIso(courtParty.getDateOfBirth()))
                    .age(convertAge(courtParty.getAge()))
                    .address(transformAddressSchemaFromDefendant(courtParty.getAddress()))
                    .inCustody(null)
                    .gender(courtParty.getGender())
                    .asn(courtParty.getAsn())
                    .build();
        }
        OrganisationDetails organisationDetails = null;
        if (isNonBlank(courtParty.getOrganisationName())) {
            organisationDetails = OrganisationDetails.builder()
                    .organisationName(courtParty.getOrganisationName())
                    .organisationAddress(transformAddressSchemaFromDefendant(courtParty.getAddress()))
                    .build();
        }
        List<OffenceSchema> offences = transformOffenceSchemasFromRestrictions(
                courtParty.getOffences(), courtParty.getReportingRestrictions());
        boolean isSubjectOfApplication = isNonBlank(courtParty.getId())
                && subjectPartyId != null
                && subjectPartyId.equals(courtParty.getId().trim());
        return Party.builder()
                .partyRole(partyRole)
                .individualDetails(individualDetails)
                .offence(CollectionUtils.isEmpty(offences)? null: offences)
                .organisationDetails(organisationDetails)
                .subject(isSubjectOfApplication)
                .build();
    }

    @Override
    protected List<Application> buildApplications(Hearing hearing, CourtApplication courtApplication, List<Party> parties) {
        Application application = Application.builder()
                .applicationReference(hearing.getCaseNumber())
                .applicationType(courtApplication.getApplicationType())
                .applicationParticulars(CaTHStringUtils.stripSurroundingWhitespace(courtApplication.getApplicationParticulars()))
                .party(parties.isEmpty() ? null : parties)
                .build();
        return Collections.singletonList(application);
    }

    private List<CaseSchema> transformCases(Hearing hearing, String subjectPartyId) {
        List<CaseSchema> cases = new ArrayList<>();

        if (hearing.getDefendants() == null || hearing.getDefendants().isEmpty()) {
            return cases;
        }

        for (Defendant defendant : hearing.getDefendants()) {
            List<Party> parties = transformParties(defendant, hearing, subjectPartyId);

            List<String> reportingRestrictionDetails = getReportingRestrictionDetails(defendant);
            boolean hasReportingRestriction = hasReportingRestriction(defendant);
            CaseSchema caseSchema = CaseSchema.builder()
                    .caseUrn(hearing.getCaseNumber())
                    .reportingRestriction(hasReportingRestriction)
                    .reportingRestrictionDetails(CollectionUtils.isEmpty(reportingRestrictionDetails)?null: reportingRestrictionDetails)
                    .caseSequenceIndicator(null) // Not available in source data
                    .party(parties)
                    .build();

            cases.add(caseSchema);
        }

        return cases;
    }

    /**
     * Resolves reporting restriction details from the defendant's reportingRestrictions array (labels).
     */
    private List<String> getReportingRestrictionDetails(Defendant defendant) {
        if (defendant.getReportingRestrictions() == null || defendant.getReportingRestrictions().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> labels = defendant.getReportingRestrictions().stream()
                .map(ReportingRestriction::getLabel)
                .filter(label -> label != null && !label.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList());
        return labels.isEmpty() ? null : labels;
    }

    private boolean hasReportingRestriction(Defendant defendant) {
        if (defendant.getReportingRestrictions() == null || defendant.getReportingRestrictions().isEmpty()) {
            return false;
        }
        return defendant.getReportingRestrictions().stream()
                .anyMatch(r -> r.getLabel() != null && !r.getLabel().trim().isEmpty());
    }

    private List<Party> transformParties(Defendant defendant, Hearing hearing, String subjectPartyId) {
        List<Party> parties = new ArrayList<>();

        // Determine party role - default to DEFENDANT if not specified
        String partyRole = "DEFENDANT"; // Default role

        // Transform individual details if defendant is an individual
        IndividualDetails individualDetails = null;
        if (defendant.getFirstName() != null || defendant.getSurname() != null) {
            individualDetails = IndividualDetails.builder()
                    .individualForenames(defendant.getFirstName())
                    .individualMiddleName(null) // Not available in source data
                    .individualSurname(defendant.getSurname())
                    .dateOfBirth(convertDateOfBirthToIso(defendant.getDateOfBirth()))
                    .age(convertAge(defendant.getAge()))
                    .address(transformAddressSchemaFromDefendant(defendant.getAddress()))
                    .inCustody(null) // Not available in source data
                    .gender(defendant.getGender()) // Not available in source data
                    .asn(defendant.getAsn()) // Not available in source data
                    .build();
        }

        // Transform offences
        List<OffenceSchema> offences = transformOffenceSchemas(defendant.getOffences(), defendant);

        // Transform organisation details if defendant is an organisation
        OrganisationDetails organisationDetails = null;
        if (defendant.getOrganisationName() != null && !defendant.getOrganisationName().trim().isEmpty()) {
            organisationDetails = OrganisationDetails.builder()
                    .organisationName(defendant.getOrganisationName())
                    .organisationAddress(transformAddressSchemaFromDefendant(defendant.getAddress()))
                    .build();
        }

        boolean isSubjectOfApplication = isNonBlank(defendant.getId())
                && subjectPartyId != null
                && subjectPartyId.equals(defendant.getId().trim());

        parties.add(Party.builder()
                .partyRole(partyRole)
                .individualDetails(individualDetails)
                .offence(CollectionUtils.isEmpty(offences)? null: offences)
                .organisationDetails(organisationDetails)
                .subject(isSubjectOfApplication)
                .build());

        Party prosecutorParty = createProsecutorParty(hearing.getProsecutorType());
        if (prosecutorParty != null) {
            parties.add(prosecutorParty);
        }

        return parties;
    }

    private List<OffenceSchema> transformOffenceSchemas(List<uk.gov.hmcts.cp.models.Offence> offences, Defendant defendant) {
        if (offences == null || offences.isEmpty()) {
            return Collections.emptyList();
        }
        List<ReportingRestriction> restrictions = defendant != null ? defendant.getReportingRestrictions() : null;
        return transformOffenceSchemasFromRestrictions(offences, restrictions);
    }

    private List<OffenceSchema> transformOffenceSchemasFromRestrictions(
            List<uk.gov.hmcts.cp.models.Offence> offences, List<ReportingRestriction> reportingRestrictions) {
        if (offences == null || offences.isEmpty()) {
            return Collections.emptyList();
        }
        return offences.stream()
                .map(offence -> transformOffenceSchemaWithRestrictions(offence, reportingRestrictions))
                .collect(Collectors.toList());
    }

    /**
     * Normalises a date string for schema fields that require ISO 8601 date-time (e.g. pleaDate, convictionDate, adjournedDate).
     * If the value is date-only (YYYY-MM-DD), appends T00:00:00.000Z; otherwise returns the value unchanged (or null).
     */
    public static final String toIsoDateTimeOrNull(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        String trimmed = dateStr.trim();
        if (DATE_ONLY_PATTERN.matcher(trimmed).matches()) {
            return trimmed + "T00:00:00.000Z";
        }
        return trimmed;
    }

    /** Schema allows only GUILTY, NOT_GUILTY, NONE; map INDICATED_GUILTY to GUILTY, reject other unknowns. */
    private static String toSchemaPlea(String plea) {
        if (plea == null || plea.isBlank()) {
            return null;
        }
        String p = plea.trim();
        if ("INDICATED_GUILTY".equalsIgnoreCase(p)) {
            return "GUILTY";
        }
        if ("GUILTY".equalsIgnoreCase(p)) {
            return "GUILTY";
        }
        if ("NOT_GUILTY".equalsIgnoreCase(p)) {
            return "NOT_GUILTY";
        }
        if ("NONE".equalsIgnoreCase(p)) {
            return "NONE";
        }
        return null;
    }

    private OffenceSchema transformOffenceSchemaWithRestrictions(
            uk.gov.hmcts.cp.models.Offence offence, List<ReportingRestriction> reportingRestrictions) {
        List<String> offenceReportingDetails = null;
        Boolean offenceReportingRestriction = null;
        if (reportingRestrictions != null && !reportingRestrictions.isEmpty()) {
            offenceReportingDetails = reportingRestrictions.stream()
                    .map(ReportingRestriction::getLabel)
                    .filter(label -> label != null && !label.trim().isEmpty())
                    .map(String::trim)
                    .collect(Collectors.toList());
            offenceReportingRestriction = offenceReportingDetails != null && !offenceReportingDetails.isEmpty();
            if (offenceReportingDetails != null && offenceReportingDetails.isEmpty()) {
                offenceReportingDetails = null;
            }
        }
        return OffenceSchema.builder()
                .offenceCode(offence.getOffenceCode())
                .offenceTitle(offence.getOffenceTitle())
                .offenceWording(CaTHStringUtils.stripSurroundingWhitespace(offence.getOffenceWording()))
                .offenceMaxPen(offence.getMaxPenalty())
                .reportingRestriction(offenceReportingRestriction)
                .reportingRestrictionDetails(offenceReportingDetails)
                .convictionDate(toIsoDateTimeOrNull(offence.getConvictedOn())) // Not available in source data
                .adjournedDate(toIsoDateTimeOrNull(offence.getAdjournedDate())) // Not available in source data
                .plea(toSchemaPlea(offence.getPlea()))
                .pleaDate(toIsoDateTimeOrNull(offence.getPleaDate())) // Not available in source data
                .offenceLegislation(offence.getOffenceLegislation()) // Not available in source data
                .build();
    }

    private AddressSchema transformAddressSchemaFromDefendant(uk.gov.hmcts.cp.models.Address address) {
        if (address == null) {
            return null;
        }

        return transformAddressSchemaFromStrings(
                address.getAddress1(),
                address.getAddress2(),
                address.getAddress3(),
                address.getAddress4(),
                address.getAddress5(),
                address.getPostcode()
        );
    }

    private AddressSchema transformAddressSchemaFromStrings(String address1, String address2) {
        return transformAddressSchemaFromStrings(address1, address2, null, null, null, null);
    }

    private AddressSchema transformAddressSchemaFromStrings(String address1, String address2, String address3, String address4, String address5, String postcode) {
        List<String> lines = new ArrayList<>();

        if (address1 != null && !address1.trim().isEmpty()) {
            lines.add(address1.trim());
        }
        if (address2 != null && !address2.trim().isEmpty()) {
            lines.add(address2.trim());
        }
        if (address3 != null && !address3.trim().isEmpty()) {
            lines.add(address3.trim());
        }
        if (address4 != null && !address4.trim().isEmpty()) {
            lines.add(address4.trim());
        }
        if (address5 != null && !address5.trim().isEmpty()) {
            lines.add(address5.trim());
        }

        // Extract town and county from address lines if possible
        // This is a simplified approach - may need adjustment based on actual data format
        String town = null;
        String county = null;
        if (lines.size() >= 2) {
            town = lines.get(lines.size() - 2);
            if (lines.size() >= 3) {
                county = lines.get(lines.size() - 3);
            }
        }

        return AddressSchema.builder()
                .line(lines)
                .town(town)
                .county(county)
                .postCode(postcode)
                .build();
    }

    private String convertDateOfBirthToIso(String dob) {
        if (dob == null || dob.trim().isEmpty()) {
            return null;
        }

        // Normalise "Sept." to "Sept " only; Locale.UK expects "Sept" for September, not "Sep"
        String normalised = dob.trim().replace("Sept.", "Sept ");

        try {
            // Try abbreviated month first: "5 Jan 2006", "20 Sept 1978"
            LocalDate date = LocalDate.parse(normalised, DOB_FORMATTER);
            return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception e1) {
            try {
                // Fallback: full month name e.g. "11 September 1972"
                LocalDate date = LocalDate.parse(normalised, DOB_FORMATTER_FULL_MONTH);
                return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e2) {
                log.warn("Failed to parse date of birth ", e1);
                return null;
            }
        }
    }

    private Integer convertAge(String age) {
        if (age == null || age.trim().isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(age.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse age", e);
            return null;
        }
    }
}
