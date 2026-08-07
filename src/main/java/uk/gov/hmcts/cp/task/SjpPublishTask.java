package uk.gov.hmcts.cp.task;

import jakarta.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;
import uk.gov.hmcts.cp.services.AzureBlobService;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPublisher;
import uk.gov.hmcts.cp.services.CourtListStatusUpdater;
import uk.gov.hmcts.cp.services.JsonSchemaValidatorService;
import uk.gov.hmcts.cp.services.PublicationSchema;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;
import uk.gov.hmcts.cp.services.sjp.SjpCourtListPublishService;
import uk.gov.hmcts.cp.services.sjp.SjpToCathPayloadTransformer;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;

/**
 * Async worker for SJP court list publishing, queued by
 * {@link uk.gov.hmcts.cp.services.sjp.SjpTaskTriggerService}. Mirrors
 * {@link CourtListPublishAndPDFGenerationTask}'s CaTH-send step: transforms and sanitizes the
 * payload, uploads it to Azure blob storage (using the same blob-name convention as the
 * standard flow, {@link CaTHService#buildBlobName}, so the existing cleanup job already covers
 * SJP blobs) before publishing, then sends to CaTH. A repeat publish for the same day and fused
 * list type always re-transforms, re-uploads and re-sends, overwriting the same row — same as
 * the standard flow, no content-based dedup.
 *
 * <p>Tracked in {@code court_list_publish_status} (shared with the standard flow) via
 * {@link CourtListStatusRepository} — {@code courtCentreId} is always null for these rows
 * (SJP has no court-centre concept) and {@code fileStatus}/file-related columns are unused
 * (no PDF generation for SJP).
 */
@Task("SJP_PUBLISH_TASK")
@Component
public class SjpPublishTask implements ExecutableTask {

    private static final Logger logger = LoggerFactory.getLogger(SjpPublishTask.class);

    private static final String SENSITIVITY_PUBLIC = "PUBLIC";
    private static final String SENSITIVITY_CLASSIFIED = "CLASSIFIED";
    private static final String DOCUMENT_NAME_PUBLIC = "SJP Public list";
    private static final String DOCUMENT_NAME_PRESS = "SJP Press list";
    private static final String PROVENANCE = "COMMON_PLATFORM";
    private static final String TYPE_LIST = "LIST";

    /** Press variants (full and delta) carry CLASSIFIED sensitivity and the press schema. */
    private static final java.util.Set<String> PRESS_LIST_TYPES = java.util.Set.of(
            SjpCourtListPublishService.SJP_PRESS_LIST, SjpCourtListPublishService.SJP_DELTA_PRESS_LIST);

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    private final CourtListStatusUpdater statusUpdater;
    private final SjpToCathPayloadTransformer transformer;
    private final CourtListPublisher courtListPublisher;
    private final DocumentSanitizer documentSanitizer;
    private final JsonSchemaValidatorService jsonSchemaValidatorService;
    private final Optional<AzureBlobService> azureBlobService;

    public SjpPublishTask(CourtListStatusUpdater statusUpdater,
                           SjpToCathPayloadTransformer transformer,
                           CourtListPublisher courtListPublisher,
                           DocumentSanitizer documentSanitizer,
                           JsonSchemaValidatorService jsonSchemaValidatorService,
                           Optional<AzureBlobService> azureBlobService) {
        this.statusUpdater = statusUpdater;
        this.transformer = transformer;
        this.courtListPublisher = courtListPublisher;
        this.documentSanitizer = documentSanitizer;
        this.jsonSchemaValidatorService = jsonSchemaValidatorService;
        this.azureBlobService = azureBlobService;
    }

    @Override
    public ExecutionInfo execute(ExecutionInfo executionInfo) {
        logger.info("Executing SJP_PUBLISH_TASK [job {}]", executionInfo);

        JsonObject jobData = executionInfo.getJobData();
        UUID courtListId = jobData != null ? extractCourtListId(jobData) : null;

        try {
            if (jobData == null) {
                logger.warn("SJP_PUBLISH_TASK executed with no job data");
            } else {
                publish(courtListId, jobData);
            }
        } catch (Exception e) {
            logger.error("Error publishing SJP court list for courtListId: {}", courtListId, e);
            if (courtListId != null) {
                statusUpdater.markPublishFailed(courtListId, e);
            }
        }

        return executionInfo().from(executionInfo).withExecutionStatus(COMPLETED).build();
    }

    private void publish(UUID courtListId, JsonObject jobData) throws Exception {
        String listType = jobData.getString(JobDataConstant.SJP_LIST_TYPE, null);
        String payloadJson = jobData.getString(JobDataConstant.SJP_PAYLOAD, null);
        String language = jobData.containsKey(JobDataConstant.SJP_LANGUAGE)
                ? jobData.getString(JobDataConstant.SJP_LANGUAGE) : null;
        String requestType = jobData.containsKey(JobDataConstant.SJP_REQUEST_TYPE)
                ? jobData.getString(JobDataConstant.SJP_REQUEST_TYPE) : null;

        if (courtListId == null || listType == null || payloadJson == null) {
            logger.warn("Missing required job data for SJP publish task, courtListId={}, listType={}", courtListId, listType);
            return;
        }

        SjpListPayload payload = OBJECT_MAPPER.readValue(payloadJson, SjpListPayload.class);

        boolean isPressList = PRESS_LIST_TYPES.contains(listType);
        String documentName = isPressList ? DOCUMENT_NAME_PRESS : DOCUMENT_NAME_PUBLIC;
        // Forwarded to CaTH verbatim: SjpListType mirrors CaTH's ListType one-to-one, so
        // collapsing delta variants here would make CaTH render delta content with the
        // full-list template.
        String cathListType = listType;
        String sensitivity = isPressList ? SENSITIVITY_CLASSIFIED : SENSITIVITY_PUBLIC;

        String payloadLanguage = Boolean.TRUE.equals(payload.getIsWelsh()) ? "WELSH" : "ENGLISH";
        String lang = (language != null && !language.isBlank()) ? language : payloadLanguage;

        String transformedPayload = documentSanitizer.sanitize(transformer.transform(payload, documentName));

        PublicationSchema schema = isPressList ? PublicationSchema.SJP_PRESS : PublicationSchema.SJP_PUBLIC;
        jsonSchemaValidatorService.validate(transformedPayload, schema);

        uploadPayloadToBlob(transformedPayload, courtListId);

        DtsMeta meta = buildDtsMeta(cathListType, sensitivity, lang, requestType, payload.getCourtIdNumeric());
        int status = courtListPublisher.publish(transformedPayload, meta);
        logger.info("SJP court list published to CaTH, courtListId={}, listType={}, language={}, status={}",
                courtListId, listType, lang, status);

        if (status >= 200 && status < 300) {
            statusUpdater.markPublishSuccessful(courtListId);
        } else {
            RuntimeException cathFailure = new RuntimeException("CaTH returned status " + status);
            logger.error("CaTH publish failed for courtListId: {}, listType: {}, status: {}", courtListId, listType, status, cathFailure);
            statusUpdater.markPublishFailed(courtListId, cathFailure);
        }
    }

    private void uploadPayloadToBlob(String payload, UUID courtListId) {
        azureBlobService.ifPresentOrElse(
                blobService -> {
                    try {
                        blobService.uploadJson(payload, CaTHService.buildBlobName(courtListId));
                    } catch (Exception e) {
                        logger.error("Error uploading SJP payload to blob storage, continuing with publish", e);
                    }
                },
                () -> logger.debug("Azure Blob Service not available, skipping SJP payload upload")
        );
    }

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

    private UUID extractCourtListId(JsonObject jobData) {
        try {
            String value = jobData.getString(JobDataConstant.SJP_LIST_ID, null);
            return value != null ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid UUID format for courtListId: {}", jobData.getString(JobDataConstant.SJP_LIST_ID, null), e);
            return null;
        } catch (Exception e) {
            logger.warn("Could not extract courtListId from JsonObject", e);
            return null;
        }
    }
}
