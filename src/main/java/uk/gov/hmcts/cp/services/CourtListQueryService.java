package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourtListQueryService {

    private final CourtListDataService courtListDataService;
    private final StandardCourtListTransformationService transformationService;
    private final OnlinePublicCourtListTransformationService onlinePublicCourtListTransformationService;
    private final JsonSchemaValidatorService jsonSchemaValidatorService;
    private final DocumentSanitizer courtListDocumentSanitizer;

    /**
     * Transforms an existing payload into CourtListDocument (no remote fetch).
     * Use when the payload was already obtained so that getCourtListPayload is not called again.
     */
    public CourtListDocument buildCourtListDocumentFromPayload(final CourtListPayload payload, final CourtListType listId) {
        if (CourtListType.ONLINE_PUBLIC.equals(listId)) {
            log.info("Using PublicCourtListTransformationService for PUBLIC list type");
            final CourtListDocument document = onlinePublicCourtListTransformationService.transform(payload);
            final CourtListDocument sanitized = courtListDocumentSanitizer.sanitize(document);
            jsonSchemaValidatorService.validate(sanitized, PublicationSchema.ONLINE_PUBLIC);
            return sanitized;
        }
        log.info("Using CourtListTransformationService for list type: {}", listId);
        final CourtListDocument document = transformationService.transform(payload);
        final CourtListDocument sanitized = courtListDocumentSanitizer.sanitize(document);
        jsonSchemaValidatorService.validate(sanitized, PublicationSchema.STANDARD);
        return sanitized;
    }

    public @NotNull CourtListPayload getCourtListPayload(final CourtListType listId, final String courtCentreId, final String startDate, final String endDate, final String cjscppuid, final boolean includeApplications) {
        return courtListDataService.getCourtListPayload(listId, courtCentreId, startDate, endDate, cjscppuid, includeApplications);
    }
}

