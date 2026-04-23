package uk.gov.hmcts.cp.services;

import static org.apache.commons.collections.CollectionUtils.isEmpty;

import uk.gov.hmcts.cp.models.*;
import uk.gov.hmcts.cp.models.transformed.schema.*;
import uk.gov.hmcts.cp.util.CaTHStringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlinePublicCourtListTransformationService extends BaseCourtListTransformationService {

    @Override
    protected String getTransformLogMessage() {
        return "Transforming progression public court list payload to document format";
    }

    @Override
    protected AddressSchema buildVenueAddressFromPayload(final CourtListPayload payload) {
        final List<String> lines = new ArrayList<>();
        if (isNonBlank(payload.getAddress1())) {
            lines.add(payload.getAddress1().trim());
        }

        if (isNonBlank(payload.getAddress2())) {
            lines.add(payload.getAddress2().trim());
        }

        if (isNonBlank(payload.getAddress3())) {
            lines.add(payload.getAddress3().trim());
        }

        if (isNonBlank(payload.getAddress4())) {
            lines.add(payload.getAddress4().trim());
        }

        if (isNonBlank(payload.getAddress5())) {
            lines.add(payload.getAddress5().trim());
        }

        final String postcode = payload.getPostcode() != null ? payload.getPostcode().trim() : "";
        return AddressSchema.builder()
                .line(lines.isEmpty() ? new ArrayList<>() : lines)
                .postCode(postcode)
                .build();
    }

    @Override
    protected CourtHouse buildCourtHouse(final List<CourtRoomSchema> courtRooms, final CourtListPayload payload) {
        return CourtHouse.builder()
                .courtRoom(courtRooms)
                .build();
    }

    @Override
    protected HearingSchema transformHearing(final Hearing hearing) {
        final ApplicationTransformResult applicationTransformResult = transformApplications(hearing);
        final List<Application> applications = applicationTransformResult.applications();
        final boolean hasApplications = applications != null && !applications.isEmpty();

        List<CaseSchema> cases;
        if (hasApplications) {
            cases = Collections.emptyList();
        } else {
            cases = transformCases(hearing, applicationTransformResult.subjectPartyId());
        }

        if (cases.isEmpty() && !hasApplications) {
            return null;
        }

        final List<String> channels = new ArrayList<>();
        return HearingSchema.builder()
                .hearingType(hearing.getHearingType())
                .caseList(cases)
                .channel(channels)
                .application(applicationTransformResult.applications())
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
        if (isNonBlank(courtParty.getFirstName()) || isNonBlank(courtParty.getSurname()) || isNonBlank(courtParty.getName())) {
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
        List<OffenceSchema> offences = transformApplicationPartyOffencesForPublicList(
                courtParty.getOffences(), courtParty.getReportingRestrictions());
        boolean isSubjectOfApplication = isNonBlank(courtParty.getId())
                && subjectPartyId != null
                && subjectPartyId.equals(courtParty.getId().trim());

        return Party.builder()
                .partyRole(partyRole)
                .individualDetails(individualDetails)
                .offence(isEmpty(offences) ? null: offences)
                .organisationDetails(organisationDetails)
                .subject(isSubjectOfApplication)
                .build();
    }

    private String convertDateOfBirthToIso(final String dob) {
        if (dob == null || dob.trim().isEmpty()) {
            return null;
        }
        try {
            final java.time.LocalDate date = java.time.LocalDate.parse(dob.trim(), java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK));
            return date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (final Exception e) {
            try {
                final java.time.LocalDate date = java.time.LocalDate.parse(dob.trim(), java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.UK));
                return date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (final Exception e2) {
                log.warn("Failed to parse date of birth", e);
                return null;
            }
        }
    }

    private Integer convertAge(final String age) {
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

    private AddressSchema transformAddressSchemaFromDefendant(Address address) {
        if (address == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (isNonBlank(address.getAddress1())) {
            lines.add(address.getAddress1().trim());
        }

        if (isNonBlank(address.getAddress2())) {
            lines.add(address.getAddress2().trim());
        }

        if (isNonBlank(address.getAddress3())) {
            lines.add(address.getAddress3().trim());
        }

        if (isNonBlank(address.getAddress4())) {
            lines.add(address.getAddress4().trim());
        }

        if (isNonBlank(address.getAddress5())) {
            lines.add(address.getAddress5().trim());
        }

        final String postcode = address.getPostcode() != null ? address.getPostcode().trim() : null;
        return AddressSchema.builder()
                .line(lines.isEmpty() ? new ArrayList<>() : lines)
                .postCode(postcode)
                .build();
    }

    private List<OffenceSchema> transformApplicationPartyOffencesForPublicList(
            final List<uk.gov.hmcts.cp.models.Offence> offences, final List<ReportingRestriction> reportingRestrictions) {
        if (offences == null || offences.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> details = null;
        Boolean restriction = false;
        if (reportingRestrictions != null && !reportingRestrictions.isEmpty()) {
            details = reportingRestrictions.stream()
                    .map(ReportingRestriction::getLabel)
                    .filter(label -> label != null && !label.trim().isEmpty())
                    .map(String::trim)
                    .collect(Collectors.toList());
            restriction = details != null && !details.isEmpty();
            if (details != null && details.isEmpty()) {
                details = null;
            }
        }
        final List<String> finalDetails = details;
        final Boolean finalRestriction = restriction;
        return offences.stream()
                .map(o -> OffenceSchema.builder()
                        .offenceTitle(o.getOffenceTitle())
                        .reportingRestriction(finalRestriction)
                        .reportingRestrictionDetails(finalDetails)
                        .build())
                .collect(Collectors.toList());
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

    private List<CaseSchema> transformCases(final Hearing hearing, final String subjectPartyId) {
        final List<CaseSchema> cases = new ArrayList<>();

        if (hearing.getDefendants() == null || hearing.getDefendants().isEmpty()) {
            return cases;
        }

        for (final Defendant defendant : hearing.getDefendants()) {
            final List<Party> parties = new ArrayList<>();

            // Only include basic individual details (name only for public lists)
            IndividualDetails individualDetails = null;
            if (defendant.getFirstName() != null || defendant.getSurname() != null) {
                individualDetails = IndividualDetails.builder()
                        .individualForenames(defendant.getFirstName())
                        .individualSurname(defendant.getSurname())
                        .build();
            }

            // Organisation defendants (and org name alongside individuals): mirrors StandardCourtListTransformationService / CaTH schema
            OrganisationDetails organisationDetails = null;
            if (isNonBlank(defendant.getOrganisationName())) {
                organisationDetails = OrganisationDetails.builder()
                        .organisationName(defendant.getOrganisationName().trim())
                        .organisationAddress(transformAddressSchemaFromDefendant(defendant.getAddress()))
                        .build();
            }

            // Offence list per schema (offenceTitle only for public lists)
            final List<OffenceSchema> offences = transformOffencesForPublicList(defendant.getOffences(), defendant);

            final boolean isSubjectOfApplication = isNonBlank(defendant.getId())
                    && subjectPartyId != null
                    && subjectPartyId.equals(defendant.getId().trim());

            parties.add(Party.builder()
                    .partyRole("DEFENDANT")
                    .individualDetails(individualDetails)
                    .offence(isEmpty(offences)? null : offences)
                    .organisationDetails(organisationDetails)
                    .subject(isSubjectOfApplication)
                    .build());

            final Party prosecutorParty = createProsecutorParty(hearing.getProsecutorType());
            if (prosecutorParty != null) {
                parties.add(prosecutorParty);
            }

            final List<String> reportingRestrictionDetails = getReportingRestrictionDetails(defendant);
            final boolean hasReportingRestriction = hasReportingRestriction(defendant);
            cases.add(CaseSchema.builder()
                    .caseUrn(hearing.getCaseNumber())
                    .reportingRestriction(hasReportingRestriction)
                    .reportingRestrictionDetails(isEmpty(reportingRestrictionDetails)? null: reportingRestrictionDetails)
                    .party(parties)
                    .build());
        }

        return cases;
    }

    /**
     * Resolves reporting restriction details from the defendant's reportingRestrictions array (labels).
     */
    private List<String> getReportingRestrictionDetails(final Defendant defendant) {
        if (defendant.getReportingRestrictions() == null || defendant.getReportingRestrictions().isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> labels = defendant.getReportingRestrictions().stream()
                .map(ReportingRestriction::getLabel)
                .filter(label -> label != null && !label.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList());
        return labels.isEmpty() ? null : labels;
    }

    private boolean hasReportingRestriction(final Defendant defendant) {
        if (defendant.getReportingRestrictions() == null || defendant.getReportingRestrictions().isEmpty()) {
            return false;
        }
        return defendant.getReportingRestrictions().stream()
                .anyMatch(r -> r.getLabel() != null && !r.getLabel().trim().isEmpty());
    }

    /**
     * Transform offences for public list: schema only requires offenceTitle per offence item;
     * reporting restriction fields are populated from the defendant when present.
     */
    private List<OffenceSchema> transformOffencesForPublicList(final List<uk.gov.hmcts.cp.models.Offence> offences, final Defendant defendant) {
        if (offences == null || offences.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> offenceReportingDetails = null;
        Boolean offenceReportingRestriction = null;
        if (defendant != null && defendant.getReportingRestrictions() != null && !defendant.getReportingRestrictions().isEmpty()) {
            offenceReportingDetails = defendant.getReportingRestrictions().stream()
                    .map(ReportingRestriction::getLabel)
                    .filter(label -> label != null && !label.trim().isEmpty())
                    .map(String::trim)
                    .collect(Collectors.toList());
            offenceReportingRestriction = offenceReportingDetails != null && !offenceReportingDetails.isEmpty();
            if (offenceReportingDetails != null && offenceReportingDetails.isEmpty()) {
                offenceReportingDetails = null;
            }
        }
        final List<String> details = offenceReportingDetails;
        final Boolean restriction = offenceReportingRestriction;
        return offences.stream()
                .map(o -> OffenceSchema.builder()
                        .offenceTitle(o.getOffenceTitle())
                        .reportingRestriction(restriction)
                        .reportingRestrictionDetails(details)
                        .build())
                .collect(Collectors.toList());
    }
}
