package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.services.CourtListPublisher;
import uk.gov.hmcts.cp.services.JsonSchemaValidatorService;
import uk.gov.hmcts.cp.services.PublicationSchema;
import uk.gov.hmcts.cp.services.SchemaValidationException;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Publishes SJP court lists to CaTH for the two in-scope event types:
 * <ul>
 *   <li>SJP_PUBLIC_LIST – triggered by public.sjp.pending-cases-public-list-generated
 *       (published to CaTH list type SJP_PUBLIC_LIST)</li>
 *   <li>SJP_PRESS_LIST   – triggered by public.sjp.pending-cases-press-list-generated
 *       (mapped to CaTH list type SJP_PRESS_LIST)</li>
 * </ul>
 * The press transparency report (public.sjp.press-transparency-report-generated) is out of
 * scope and remains in Staging PubHub.
 */
@Service
public class SjpCourtListPublishService {

    private static final Logger LOG = LoggerFactory.getLogger(SjpCourtListPublishService.class);
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String PROVENANCE = "COMMON_PLATFORM";
    private static final String TYPE_LIST = "LIST";
    private static final String CATH_LIST_TYPE_PUBLIC = "SJP_PUBLIC_LIST";
    private static final String CATH_LIST_TYPE_PRESS = "SJP_PRESS_LIST";
    private static final String SENSITIVITY_PUBLIC = "PUBLIC";
    private static final String SENSITIVITY_CLASSIFIED = "CLASSIFIED";
    private static final String DOCUMENT_NAME_PUBLIC = "SJP Public list";
    private static final String DOCUMENT_NAME_PRESS = "SJP Press list";
    public static final String SJP_PUBLIC_LIST = "SJP_PUBLIC_LIST";
    public static final String SJP_PRESS_LIST = "SJP_PRESS_LIST";

    private final SjpToCathPayloadTransformer transformer;
    private final CourtListPublisher courtListPublisher;
    private final DocumentSanitizer documentSanitizer;
    private final JsonSchemaValidatorService jsonSchemaValidatorService;
    private final boolean cathPublishingEnabled;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    public SjpCourtListPublishService(
            SjpToCathPayloadTransformer transformer,
            CourtListPublisher courtListPublisher,
            DocumentSanitizer documentSanitizer,
            JsonSchemaValidatorService jsonSchemaValidatorService,
            @Value("${cath.publishing-enabled:false}") boolean cathPublishingEnabled) {
        this.transformer = transformer;
        this.courtListPublisher = courtListPublisher;
        this.documentSanitizer = documentSanitizer;
        this.jsonSchemaValidatorService = jsonSchemaValidatorService;
        this.cathPublishingEnabled = cathPublishingEnabled;
    }

    /**
     * Publish SJP court list to CaTH.
     *
     * <p>Language is derived from {@code listPayload.isWelsh} — {@code true} → "WELSH", otherwise
     * "ENGLISH" — mirroring the court-centre flag used by the non-SJP publishing flow.
     * An explicit {@code language} argument overrides the payload-derived value when non-blank.
     *
     * @param listType    SJP_PUBLIC_LIST or SJP_PRESS_LIST
     * @param language    optional override (default: derived from listPayload.isWelsh)
     * @param requestType optional request type (e.g. "FULL"); passed through to DtsMeta
     * @param listPayload required for CaTH publish (generatedDateAndTime, readyCases); can be Map or POJO from API
     * @return status (ACCEPTED/FAILED), listType, message
     */
    public SjpPublishResult publishSjpCourtList(
            String listType,
            String language,
            String requestType,
            Object listPayload) {
        LOG.info("SJP court list publish request for listType: {}", Encode.forJava(listType));

        if (!cathPublishingEnabled) {
            LOG.debug("CaTH publishing is disabled (CATH_PUBLISHING_ENABLED=false), skipping SJP CaTH send");
            return SjpPublishResult.accepted(listType, "CaTH publishing is disabled");
        }

        SjpListPayload payload;
        if (listPayload == null) {
            return SjpPublishResult.failed(listType, "listPayload is required to publish to CaTH");
        }
        try {
            payload = OBJECT_MAPPER.convertValue(listPayload, SjpListPayload.class);
        } catch (Exception e) {
            LOG.warn("Invalid listPayload: {}", Encode.forJava(e.getMessage()));
            return SjpPublishResult.failed(listType, "Invalid listPayload: " + e.getMessage());
        }

        if (payload.getReadyCases() == null || payload.getReadyCases().isEmpty()) {
            return SjpPublishResult.accepted(listType, "listPayload has no readyCases; nothing to publish");
        }

        try {
            boolean isPressList = SJP_PRESS_LIST.equals(listType);
            String documentName = isPressList ? DOCUMENT_NAME_PRESS : DOCUMENT_NAME_PUBLIC;
            String cathListType = isPressList ? CATH_LIST_TYPE_PRESS : CATH_LIST_TYPE_PUBLIC;
            String sensitivity = isPressList ? SENSITIVITY_CLASSIFIED : SENSITIVITY_PUBLIC;

            // Derive language from isWelsh on the payload (mirrors CaTHService / non-SJP flow).
            // An explicit language argument takes precedence when provided.
            String payloadLanguage = Boolean.TRUE.equals(payload.getIsWelsh()) ? "WELSH" : "ENGLISH";
            String lang = (language != null && !language.isBlank()) ? language : payloadLanguage;

            String payloadJson = documentSanitizer.sanitize(transformer.transform(payload, documentName));
            PublicationSchema schema = isPressList ? PublicationSchema.SJP_PRESS : PublicationSchema.SJP_PUBLIC;
            jsonSchemaValidatorService.validate(payloadJson, schema);
            DtsMeta meta = buildDtsMeta(cathListType, sensitivity, lang, requestType, payload.getCourtIdNumeric());

            int status = courtListPublisher.publish(payloadJson, meta);
            LOG.info("SJP court list published to CaTH, listType={}, language={}, requestType={}, status={}",
                    Encode.forJava(listType), Encode.forJava(lang), Encode.forJava(requestType), status);

            if (status >= 200 && status < 300) {
                return SjpPublishResult.accepted(listType, "SJP court list published to CaTH");
            }
            return SjpPublishResult.failed(listType, "CaTH returned status " + status);
        } catch (SchemaValidationException e) {
            LOG.error("SJP payload failed schema validation for listType={}: {}", listType, e.getMessage());
            return SjpPublishResult.failed(listType, "Payload failed schema validation: " + e.getMessage());
        } catch (Exception e) {
            LOG.error("Failed to publish SJP court list to CaTH: {}", Encode.forJava(e.getMessage()), e);
            return SjpPublishResult.failed(listType, "Failed to publish to CaTH: " + e.getMessage());
        }
    }

    /**
     * Same court id resolution as {@link uk.gov.hmcts.cp.services.CaTHService#sendCourtListToCaTH}:
     * use numeric id from payload when present, otherwise {@code "0"}.
     * {@code requestType} (e.g. "FULL") is passed through to DtsMeta when provided.
     */
    private static DtsMeta buildDtsMeta(String listType, String sensitivity, String language,
                                        String requestType, String courtIdNumeric) {
        final String courtIdForMeta = courtIdNumeric != null && !courtIdNumeric.isBlank()
                ? courtIdNumeric
                : "0";
        Instant now = Instant.now();
        String contentDate = now.toString();
        String displayTo = now.plus(24, ChronoUnit.HOURS).toString();
        return DtsMeta.builder()
                .provenance(PROVENANCE)
                .type(TYPE_LIST)
                .listType(listType)
                .courtId(courtIdForMeta)
                .contentDate(contentDate)
                .language(language)
                .sensitivity(sensitivity)
                .displayFrom(contentDate)
                .displayTo(displayTo)
                .requestType(requestType)
                .build();
    }

    @lombok.Value
    public static class SjpPublishResult {
        String status;
        String listType;
        String message;

        public static SjpPublishResult accepted(String listType, String message) {
            return new SjpPublishResult(STATUS_ACCEPTED, listType, message);
        }

        public static SjpPublishResult failed(String listType, String message) {
            return new SjpPublishResult(STATUS_FAILED, listType, message);
        }
    }
}
