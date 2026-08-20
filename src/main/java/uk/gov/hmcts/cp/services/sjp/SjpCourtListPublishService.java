package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.SjpListType;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates and accepts SJP publish requests (full/delta, public/press); the transform, blob
 * upload, and CaTH send are queued as an async job ({@link SjpTaskTriggerService} /
 * {@code SjpPublishTask}), same split as the standard flow. Tracked in the shared
 * {@code court_list_publish_status} table, keyed by the fused {@link CourtListType} (see
 * {@link SjpStatusListTypeMapper}) plus publishDate; {@code courtCentreId} is always null
 * (SJP is national).
 */
@Service
public class SjpCourtListPublishService {

    private static final Logger LOG = LoggerFactory.getLogger(SjpCourtListPublishService.class);
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_FAILED = "FAILED";
    private final CourtListStatusRepository repository;
    private final SjpTaskTriggerService sjpTaskTriggerService;
    private final boolean cathPublishingEnabled;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    public SjpCourtListPublishService(
            CourtListStatusRepository repository,
            SjpTaskTriggerService sjpTaskTriggerService,
            @Value("${cath.publishing-enabled:false}") boolean cathPublishingEnabled) {
        this.repository = repository;
        this.sjpTaskTriggerService = sjpTaskTriggerService;
        this.cathPublishingEnabled = cathPublishingEnabled;
    }

    /**
     * Accepts an SJP list for publishing; only request-level validation happens here (transform,
     * upload, and CaTH send happen later in {@code SjpPublishTask}).
     *
     * @param listType    the SJP list variant being published
     * @param language    optional override (default: derived from listPayload.isWelsh)
     * @param requestType optional request type (e.g. "FULL"); passed through to DtsMeta
     * @param listPayload required for CaTH publish (generatedDateAndTime, readyCases); can be Map or POJO from API
     * @return status (ACCEPTED/FAILED), listType, message
     */
    public SjpPublishResult publishSjpCourtList(
            SjpListType listType,
            String language,
            String requestType,
            Object listPayload) {
        LOG.info("SJP court list publish request for listType: {}", listType);

        if (!cathPublishingEnabled) {
            LOG.debug("CaTH publishing is disabled (CATH_PUBLISHING_ENABLED=false), skipping SJP CaTH send");
            return SjpPublishResult.accepted(listType, "CaTH publishing is disabled");
        }

        if (listType == null) {
            return SjpPublishResult.failed(null, "listType is required to publish to CaTH");
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
            String lang = resolveLanguage(language, payload);
            CourtListType fusedListType = SjpStatusListTypeMapper.toCourtListType(listType, lang);
            UUID courtListId = findOrCreateCourtListId(fusedListType, publishDate);

            String payloadJson = OBJECT_MAPPER.writeValueAsString(payload);
            sjpTaskTriggerService.triggerSjpPublishTask(
                    courtListId, courtIdNumeric, listType, publishDate, language, requestType, payloadJson);

            LOG.info("SJP court list publish request queued, courtListId={}, listType={}",
                    courtListId, listType);
            return SjpPublishResult.accepted(listType, "SJP court list publish request accepted for processing");
        } catch (Exception e) {
            LOG.error("Failed to queue SJP court list for publishing: {}", Encode.forJava(e.getMessage()), e);
            return SjpPublishResult.failed(listType, "Failed to queue SJP court list for publishing: " + e.getMessage());
        }
    }

    /**
     * Looks up an existing record by (publishDate, fused courtListType) — courtCentreId is always
     * null for SJP rows, since SJP is a national list with no court-centre concept — and reuses
     * its {@code courtListId}, otherwise creates a new one. Same lookup-and-reuse pattern as
     * {@code CourtListPublishStatusService#createOrUpdate}.
     */
    private UUID findOrCreateCourtListId(CourtListType fusedListType, LocalDate publishDate) {
        Optional<CourtListStatusEntity> existing = repository.findByPublishDateAndCourtListType(
                publishDate, fusedListType);
        if (existing.isPresent()) {
            CourtListStatusEntity entity = existing.get();
            entity.setPublishStatus(Status.REQUESTED);
            entity.setLastUpdated(Instant.now());
            repository.save(entity);
            return entity.getCourtListId();
        }
        UUID courtListId = UUID.randomUUID();
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId, null, Status.REQUESTED, null, fusedListType, Instant.now());
        entity.setPublishDate(publishDate);
        repository.save(entity);
        return courtListId;
    }

    /**
     * use numeric id from payload when present, otherwise {@code "0"}.
     */
    private static String normalizeCourtId(String courtIdNumeric) {
        return courtIdNumeric != null && !courtIdNumeric.isBlank() ? courtIdNumeric : "0";
    }

    /** Explicit language argument wins; otherwise derived from listPayload.isWelsh. */
    private static String resolveLanguage(String language, SjpListPayload payload) {
        if (language != null && !language.isBlank()) {
            return language;
        }
        return Boolean.TRUE.equals(payload.getIsWelsh()) ? "WELSH" : "ENGLISH";
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
        SjpListType listType;
        String message;

        public static SjpPublishResult accepted(SjpListType listType, String message) {
            return new SjpPublishResult(STATUS_ACCEPTED, listType, message);
        }

        public static SjpPublishResult failed(SjpListType listType, String message) {
            return new SjpPublishResult(STATUS_FAILED, listType, message);
        }
    }
}
