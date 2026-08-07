package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.domain.sjp.SjpPublishStatusEntity;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.SjpPublishStatusRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts SJP court list publish requests for the two in-scope event types:
 * <ul>
 *   <li>SJP_PUBLIC_LIST – triggered by public.sjp.pending-cases-public-list-generated
 *       (published to CaTH list type SJP_PUBLIC_LIST)</li>
 *   <li>SJP_PRESS_LIST   – triggered by public.sjp.pending-cases-press-list-generated
 *       (mapped to CaTH list type SJP_PRESS_LIST)</li>
 * </ul>
 * The press transparency report (public.sjp.press-transparency-report-generated) is out of
 * scope and remains in Staging PubHub.
 *
 * <p>Validation happens synchronously; the actual transform, blob-storage upload, and CaTH
 * send are queued as an async job ({@link SjpPublishTask} via {@link SjpTaskTriggerService}),
 * matching the standard/online-public court list flow ({@code CourtListTaskTriggerService} /
 * {@code CourtListPublishAndPDFGenerationTask}). The dedup key (courtIdNumeric, listType,
 * publishDate) reuses the same {@code sjpListId} for repeat requests, mirroring
 * {@code CourtListPublishStatusService#createOrUpdate}.
 */
@Service
public class SjpCourtListPublishService {

    private static final Logger LOG = LoggerFactory.getLogger(SjpCourtListPublishService.class);
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_FAILED = "FAILED";
    public static final String SJP_PUBLIC_LIST = "SJP_PUBLIC_LIST";
    public static final String SJP_PRESS_LIST = "SJP_PRESS_LIST";

    private final SjpPublishStatusRepository repository;
    private final SjpTaskTriggerService sjpTaskTriggerService;
    private final boolean cathPublishingEnabled;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    public SjpCourtListPublishService(
            SjpPublishStatusRepository repository,
            SjpTaskTriggerService sjpTaskTriggerService,
            @Value("${cath.publishing-enabled:false}") boolean cathPublishingEnabled) {
        this.repository = repository;
        this.sjpTaskTriggerService = sjpTaskTriggerService;
        this.cathPublishingEnabled = cathPublishingEnabled;
    }

    /**
     * Accept an SJP court list for publishing to CaTH.
     *
     * <p>Only request-level validation (payload shape, non-empty readyCases) happens here;
     * the transform, blob upload, schema validation, and CaTH send all happen later in
     * {@link SjpPublishTask} once the job is picked up.
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

        if (listPayload == null) {
            return SjpPublishResult.failed(listType, "listPayload is required to publish to CaTH");
        }

        SjpListPayload payload;
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
            String courtIdNumeric = normalizeCourtId(payload.getCourtIdNumeric());
            LocalDate publishDate = deriveDate(payload.getGeneratedDateAndTime());
            UUID sjpListId = findOrCreateSjpListId(courtIdNumeric, listType, publishDate);

            String payloadJson = OBJECT_MAPPER.writeValueAsString(payload);
            sjpTaskTriggerService.triggerSjpPublishTask(
                    sjpListId, courtIdNumeric, listType, publishDate, language, requestType, payloadJson);

            LOG.info("SJP court list publish request queued, sjpListId={}, listType={}",
                    sjpListId, Encode.forJava(listType));
            return SjpPublishResult.accepted(listType, "SJP court list publish request accepted for processing");
        } catch (Exception e) {
            LOG.error("Failed to queue SJP court list for publishing: {}", Encode.forJava(e.getMessage()), e);
            return SjpPublishResult.failed(listType, "Failed to queue SJP court list for publishing: " + e.getMessage());
        }
    }

    /**
     * Looks up an existing record by (courtIdNumeric, listType, publishDate) and reuses its
     * {@code sjpListId} — same dedup-by-lookup pattern as
     * {@code CourtListPublishStatusService#createOrUpdate} — otherwise creates a new one.
     */
    private UUID findOrCreateSjpListId(String courtIdNumeric, String listType, LocalDate publishDate) {
        Optional<SjpPublishStatusEntity> existing = repository.findByCourtIdNumericAndListTypeAndPublishDate(
                courtIdNumeric, listType, publishDate);
        if (existing.isPresent()) {
            SjpPublishStatusEntity entity = existing.get();
            entity.setPublishStatus(Status.REQUESTED);
            entity.setLastUpdated(Instant.now());
            repository.save(entity);
            return entity.getSjpListId();
        }
        UUID sjpListId = UUID.randomUUID();
        repository.save(new SjpPublishStatusEntity(
                sjpListId, courtIdNumeric, listType, publishDate, Status.REQUESTED, Instant.now()));
        return sjpListId;
    }

    /**
     * Same court id resolution as {@link uk.gov.hmcts.cp.services.CaTHService#sendCourtListToCaTH}:
     * use numeric id from payload when present, otherwise {@code "0"}.
     */
    private static String normalizeCourtId(String courtIdNumeric) {
        return courtIdNumeric != null && !courtIdNumeric.isBlank() ? courtIdNumeric : "0";
    }

    /** Derives the dedup-key date from generatedDateAndTime, defaulting to today (UTC) if unparseable. */
    private static LocalDate deriveDate(String generatedDateAndTime) {
        if (generatedDateAndTime == null || generatedDateAndTime.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(generatedDateAndTime).toLocalDate();
        } catch (DateTimeParseException e) {
            try {
                String datePart = generatedDateAndTime.length() >= 10
                        ? generatedDateAndTime.substring(0, 10)
                        : generatedDateAndTime;
                return LocalDate.parse(datePart);
            } catch (Exception ex) {
                LOG.warn("Could not parse generatedDateAndTime '{}', defaulting to today",
                        Encode.forJava(generatedDateAndTime));
                return LocalDate.now(ZoneOffset.UTC);
            }
        }
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
