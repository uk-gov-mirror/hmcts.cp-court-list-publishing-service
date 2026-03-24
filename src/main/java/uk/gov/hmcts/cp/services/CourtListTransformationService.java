package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;

/**
 * Transforms listing payloads for the string-based query API for non-{@code PUBLIC} list ids
 * (e.g. {@code STANDARD}).
 */
@Service
@RequiredArgsConstructor
public class CourtListTransformationService {

    private final StandardCourtListTransformationService standardCourtListTransformationService;

    public CourtListDocument transform(CourtListPayload payload) {
        return standardCourtListTransformationService.transform(payload);
    }
}
