package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.services.CourtListPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Publishes SJP court list (SJP_PUBLISH_LIST and SJP_PRESS_LIST) to CaTH.
 * Replicates staging PubHub flow: transform listPayload to CaTH format, build DtsMeta, use CourtListPublisher.
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

    public static final String SJP_PUBLISH_LIST = "SJP_PUBLISH_LIST";
    public static final String SJP_PRESS_LIST = "SJP_PRESS_LIST";

    private final SjpToCathPayloadTransformer transformer;
    private final CourtListPublisher courtListPublisher;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    public SjpCourtListPublishService(
            SjpToCathPayloadTransformer transformer,
            CourtListPublisher courtListPublisher) {
        this.transformer = transformer;
        this.courtListPublisher = courtListPublisher;
    }

    /**
     * Publish SJP court list to CaTH when listPayload is provided (typically triggered by scheduler).
     *
     * @param listType    SJP_PUBLISH_LIST or SJP_PRESS_LIST
     * @param language    optional (default ENGLISH)
     * @param requestType optional (e.g. FULL)
     * @param listPayload required for CaTH publish (generatedDateAndTime, readyCases); can be Map or POJO from API
     * @return status (ACCEPTED/FAILED), listType, message
     */
    public SjpPublishResult publishSjpCourtList(
            String listType,
            String language,
            String requestType,
            Object listPayload) {
        LOG.info("SJP court list publish request for listType: {}", listType);

        SjpListPayload payload;
        if (listPayload == null) {
            return SjpPublishResult.failed(listType, "listPayload is required to publish to CaTH");
        }
        try {
            payload = OBJECT_MAPPER.convertValue(listPayload, SjpListPayload.class);
        } catch (Exception e) {
            LOG.warn("Invalid listPayload: {}", e.getMessage());
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
            String lang = (language != null && !language.isBlank()) ? language : "ENGLISH";

            String payloadJson = transformer.transform(payload, documentName);
            DtsMeta meta = buildDtsMeta(cathListType, sensitivity, lang, payload.getCourtIdNumeric());

            int status = courtListPublisher.publish(payloadJson, meta);
            LOG.info("SJP court list published to CaTH, listType={}, status={}", listType, status);

            if (status >= 200 && status < 300) {
                return SjpPublishResult.accepted(listType, "SJP court list published to CaTH");
            }
            return SjpPublishResult.failed(listType, "CaTH returned status " + status);
        } catch (Exception e) {
            LOG.error("Failed to publish SJP court list to CaTH: {}", e.getMessage(), e);
            return SjpPublishResult.failed(listType, "Failed to publish to CaTH: " + e.getMessage());
        }
    }

    /**
     * Same court id resolution as {@link uk.gov.hmcts.cp.services.CaTHService#sendCourtListToCaTH}:
     * use numeric id from payload when present, otherwise {@code "0"}.
     */
    private static DtsMeta buildDtsMeta(String listType, String sensitivity, String language, String courtIdNumeric) {
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
