package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.util.Locale;

/**
 * Fetches {@link CourtListPayload} for the string-based court list query API (e.g. STANDARD, PUBLIC).
 */
@Service
@RequiredArgsConstructor
public class ListingQueryService {

    private final CourtListDataService courtListDataService;

    /**
     * @param listId progression list id (e.g. {@code STANDARD}, {@code PUBLIC})
     */
    public CourtListPayload getCourtListPayload(
            String listId,
            String courtCentreId,
            String startDate,
            String endDate,
            String cjscppuid) {
        CourtListType type = parseListType(listId);
        return courtListDataService.getCourtListPayload(
                type, courtCentreId, startDate, endDate, cjscppuid, true);
    }

    private static CourtListType parseListType(String listId) {
        if (listId == null || listId.isBlank()) {
            throw new IllegalArgumentException("listId is required");
        }
        try {
            return CourtListType.valueOf(listId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported listId: " + listId, e);
        }
    }
}
