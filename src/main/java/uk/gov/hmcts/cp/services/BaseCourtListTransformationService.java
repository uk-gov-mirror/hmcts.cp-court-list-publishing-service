package uk.gov.hmcts.cp.services;

import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.cp.models.*;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.models.transformed.schema.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for court list transformation services.
 */
@Slf4j
public abstract class BaseCourtListTransformationService {

    /**
     * Hearing date + start time from progression are wall-clock times in the UK (GMT/BST), not UTC.
     */
    private static final ZoneId COURT_LIST_TIME_ZONE = ZoneId.of("Europe/London");

    protected static final DateTimeFormatter ISO_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /**
     * Log message used at the start of transform (e.g. "Transforming progression court list...").
     */
    protected abstract String getTransformLogMessage();

    /**
     * Build venue address from payload. Subclasses define schema-specific address format.
     */
    protected abstract AddressSchema buildVenueAddressFromPayload(CourtListPayload payload);

    /**
     * Build court house from collected court rooms. Subclasses set courtHouseName/lja or leave them unset.
     */
    protected abstract CourtHouse buildCourtHouse(List<CourtRoomSchema> courtRooms, CourtListPayload payload);

    /**
     * Transform a hearing into schema format. Subclasses define case list and hearing schema fields.
     */
    protected abstract HearingSchema transformHearing(Hearing hearing);

    public final CourtListDocument transform(CourtListPayload payload) {
        log.info(getTransformLogMessage());

        String publicationDate = java.time.OffsetDateTime.now(ZoneOffset.UTC)
                .format(ISO_DATE_TIME_FORMATTER);

        DocumentSchema document = DocumentSchema.builder()
                .publicationDate(publicationDate)
                .build();

        Venue venue = transformVenue(payload);
        List<CourtList> courtLists = transformCourtLists(payload);

        return CourtListDocument.builder()
                .document(document)
                .venue(venue)
                .courtLists(courtLists)
                .build();
    }

    protected final Venue transformVenue(CourtListPayload payload) {
        return Venue.builder()
                .venueAddress(buildVenueAddressFromPayload(payload))
                .build();
    }

    protected static boolean isNonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Extracts the subject party id from the court application or the hearing (parent).
     * Subject can be on courtApplication or on the hearing; returns null if absent or id is blank.
     */
    protected final String getSubjectPartyId(Hearing hearing) {
        CourtApplication courtApplication = hearing != null ? hearing.getCourtApplication() : null;
        CourtApplicationParty subject = courtApplication != null ? courtApplication.getSubject() : null;
        if (subject == null && hearing != null) {
            subject = hearing.getSubject();
        }
        if (subject == null) {
            return null;
        }
        String id = subject.getId();
        return isNonBlank(id) ? id.trim() : null;
    }

    /**
     * Result of transforming court application: schema Application list and subject party id (single party) for case parties.
     */
    protected record ApplicationTransformResult(List<Application> applications, String subjectPartyId) {
    }

    /**
     * Transforms hearing court application into Application list and subject party id.
     */
    protected final ApplicationTransformResult transformApplications(Hearing hearing) {
        String subjectPartyId = getSubjectPartyId(hearing);
        CourtApplication courtApplication = hearing.getCourtApplication();

        if (courtApplication == null || !isNonBlank(hearing.getCourtApplicationId())) {
            return new ApplicationTransformResult(Collections.emptyList(), subjectPartyId);
        }

        List<Party> parties = buildApplicationParties(courtApplication, subjectPartyId, hearing);
        List<Application> applications = buildApplications(hearing, courtApplication, parties);
        return new ApplicationTransformResult(applications, subjectPartyId);
    }

    /**
     * Builds the list of parties for the court application (applicant, respondents, subject).
     * Subject is taken from courtApplication if present, otherwise from the hearing (parent) so that
     * progression payloads with subject on the parent are mapped with the subject in application parties and subject=true.
     */
    protected final List<Party> buildApplicationParties(CourtApplication courtApplication, String subjectPartyId, Hearing hearing) {
        List<Party> parties = new ArrayList<>();
        if (courtApplication.getApplicant() != null) {
            Party p = buildApplicationParty(courtApplication.getApplicant(), "APPLICANT", subjectPartyId);
            if (p != null) parties.add(p);
        }
        if (courtApplication.getRespondents() != null) {
            for (CourtApplicationParty respondent : courtApplication.getRespondents()) {
                Party p = buildApplicationParty(respondent, "RESPONDENT", subjectPartyId);
                if (p != null) parties.add(p);
            }
        }
        CourtApplicationParty subjectParty = courtApplication.getSubject();
        if (subjectParty == null && hearing != null) {
            subjectParty = hearing.getSubject();
        }
        if (subjectParty != null) {
            Party p = buildApplicationParty(subjectParty, "SUBJECT", subjectPartyId);
            if (p != null) parties.add(p);
        }
        return parties;
    }

    /**
     * Builds a single Party for an application party (applicant, respondent, or subject). Sets {@link Party#getSubject()} when party id equals subject party id.
     */
    protected abstract Party buildApplicationParty(CourtApplicationParty courtParty, String partyRole, String subjectPartyId);

    /**
     * Builds the schema Application list from the hearing, court application, and pre-built parties. Subclasses define application fields (e.g. applicationParticulars).
     */
    protected abstract List<Application> buildApplications(Hearing hearing, CourtApplication courtApplication, List<Party> parties);

    protected final List<CourtList> transformCourtLists(CourtListPayload payload) {
        List<CourtList> courtLists = new ArrayList<>();
        List<CourtRoomSchema> courtRooms = collectCourtRooms(payload);

        if (!courtRooms.isEmpty()) {
            CourtHouse courtHouse = buildCourtHouse(courtRooms, payload);
            courtLists.add(CourtList.builder().courtHouse(courtHouse).build());
        }

        return courtLists;
    }

    protected final List<CourtRoomSchema> collectCourtRooms(CourtListPayload payload) {
        List<CourtRoomSchema> courtRooms = new ArrayList<>();

        if (payload.getHearingDates() != null && !payload.getHearingDates().isEmpty()) {
            for (HearingDate hearingDate : payload.getHearingDates()) {
                if (hearingDate.getCourtRooms() != null) {
                    for (CourtRoom courtRoom : hearingDate.getCourtRooms()) {
                        CourtRoomSchema courtRoomSchema = transformCourtRoom(courtRoom, hearingDate);
                        if (courtRoomSchema != null) {
                            courtRooms.add(courtRoomSchema);
                        }
                    }
                }
            }
        }

        return courtRooms;
    }

    protected final CourtRoomSchema transformCourtRoom(CourtRoom courtRoom, HearingDate hearingDate) {
        List<SessionSchema> sessions = new ArrayList<>();

        if (courtRoom.getTimeslots() != null) {
            SessionSchema session = transformSession(courtRoom, hearingDate);
            if (session != null) {
                sessions.add(session);
            }
        }

        if (sessions.isEmpty()) {
            return null;
        }

        return CourtRoomSchema.builder()
                .courtRoomName(courtRoom.getCourtRoomName())
                .session(sessions)
                .build();
    }

    protected final SessionSchema transformSession(CourtRoom courtRoom, HearingDate hearingDate) {
        List<Judiciary> judiciary = transformJudiciary(courtRoom.getJudiciaryNames());
        List<Sitting> sittings = new ArrayList<>();

        if (courtRoom.getTimeslots() != null) {
            for (Timeslot timeslot : courtRoom.getTimeslots()) {
                if (timeslot.getHearings() != null && !timeslot.getHearings().isEmpty()) {
                    Sitting sitting = transformSitting(timeslot, hearingDate);
                    if (sitting != null) {
                        sittings.add(sitting);
                    }
                }
            }
        }

        if (sittings.isEmpty()) {
            return null;
        }

        return SessionSchema.builder()
                .judiciary(judiciary)
                .sittings(sittings)
                .build();
    }

    protected final List<Judiciary> transformJudiciary(String judiciaryNames) {
        List<Judiciary> judiciary = new ArrayList<>();

        if (judiciaryNames != null && !judiciaryNames.trim().isEmpty()) {
            String[] names = judiciaryNames.split("[,;]");
            for (int i = 0; i < names.length; i++) {
                String name = names[i].trim();
                if (!name.isEmpty()) {
                    judiciary.add(Judiciary.builder()
                            .johKnownAs(name)
                            .isPresiding(i == 0)
                            .build());
                }
            }
        }

        return judiciary;
    }

    protected final Sitting transformSitting(Timeslot timeslot, HearingDate hearingDate) {
        if (timeslot.getHearings() == null || timeslot.getHearings().isEmpty()) {
            return null;
        }

        String sittingStart = convertToIsoDateTime(
                timeslot.getHearings().get(0).getStartTime(),
                hearingDate.getHearingDate());

        List<HearingSchema> hearings = new ArrayList<>();
        for (Hearing hearing : timeslot.getHearings()) {
            HearingSchema hearingSchema = transformHearing(hearing);
            if (hearingSchema != null) {
                hearings.add(hearingSchema);
            }
        }

        if (hearings.isEmpty()) {
            return null;
        }

        return Sitting.builder()
                .sittingStart(sittingStart)
                .hearing(hearings)
                .build();
    }

    /**
     * Create a PROSECUTING_AUTHORITY party when prosecutor type is non-blank. Returns null otherwise.
     */
    protected final Party createProsecutorParty(String prosecutorType) {
        if (!isNonBlank(prosecutorType)) {
            return null;
        }
        return Party.builder()
                .partyRole("PROSECUTING_AUTHORITY")
                .individualDetails(null)
                .offence(null)
                .organisationDetails(OrganisationDetails.builder()
                        .organisationName(prosecutorType.trim())
                        .organisationAddress(null)
                        .build())
                .subject(null)
                .build();
    }

    protected final String convertToIsoDateTime(String time, String date) {
        if (time == null || date == null) {
            return null;
        }

        try {
            LocalDate localDate = LocalDate.parse(date);
            String[] timeParts = time.split(":");
            int hour = timeParts.length > 0 ? Integer.parseInt(timeParts[0]) : 0;
            int minute = timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0;
            int second = timeParts.length > 2 ? Integer.parseInt(timeParts[2]) : 0;

            ZonedDateTime ukZoned = localDate.atTime(hour, minute, second).atZone(COURT_LIST_TIME_ZONE);
            return ukZoned.withZoneSameInstant(ZoneOffset.UTC).format(ISO_DATE_TIME_FORMATTER);
        } catch (Exception e) {
            String safeDateForLog = date == null ? null : date.replace("\n", "").replace("\r", "");
            String safeTimeForLog = time == null ? null : time.replace("\n", "").replace("\r", "");
            log.warn("Failed to convert date/time to ISO format: date={}, time={}", safeDateForLog, safeTimeForLog, e);
            return null;
        }
    }
}
