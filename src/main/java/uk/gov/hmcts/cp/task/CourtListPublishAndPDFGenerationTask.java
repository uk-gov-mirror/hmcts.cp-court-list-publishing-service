package uk.gov.hmcts.cp.task;

import jakarta.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.CaTHService;
import uk.gov.hmcts.cp.services.CourtListPdfHelper;
import uk.gov.hmcts.cp.services.CourtListQueryService;
import uk.gov.hmcts.cp.services.CourtListStatusUpdater;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;


@Task("PUBLISH_AND_PDF_GENERATION_TASK")
@Component
public class CourtListPublishAndPDFGenerationTask implements ExecutableTask {

    private static final Logger logger = LoggerFactory.getLogger(CourtListPublishAndPDFGenerationTask.class);

    public static final String ALERT_PATTERN = "PUBLISHING_FAILED";

    private final CourtListStatusUpdater statusUpdater;
    private final CourtListQueryService courtListQueryService;
    private final CaTHService cathService;
    private final CourtListPdfHelper pdfHelper;
    private final boolean cathPublishingEnabled;

    public CourtListPublishAndPDFGenerationTask(CourtListStatusUpdater statusUpdater,
                                                CourtListQueryService courtListQueryService,
                                                CaTHService cathService,
                                                CourtListPdfHelper pdfHelper,
                                                @Value("${cath.publishing-enabled:false}") boolean cathPublishingEnabled) {
        this.statusUpdater = statusUpdater;
        this.courtListQueryService = courtListQueryService;
        this.cathService = cathService;
        this.pdfHelper = pdfHelper;
        this.cathPublishingEnabled = cathPublishingEnabled;
    }

    @Override
    public ExecutionInfo execute(ExecutionInfo executionInfo) {
        logger.info("Executing COURT_LIST_PUBLISH_TASK [job {}]", executionInfo);

        JsonObject jobData = executionInfo.getJobData();
        UUID courtListId = jobData != null ? extractCourtListId(jobData) : null;
        String userId = extractUserId(jobData);

        CourtListPayload payload = null;
        CourtListType listId = null;
        String courtCentreId = null;
        LocalDate publishDate = null;
        if (jobData != null) {
            listId = extractCourtListType(jobData);
            courtCentreId = extractCourtCentreId(jobData);
            publishDate = extractPublishDate(jobData);
            if (listId != null && courtCentreId != null && publishDate != null) {
                try {
                    payload = courtListQueryService.getCourtListPayload(
                            listId, courtCentreId, publishDate.toString(), publishDate.toString(), userId, true);
                } catch (Exception e) {
                    logger.error("Error {} fetching court list payload", ALERT_PATTERN, e);
                }
            }
        }

        boolean cathSucceeded = tryPublishToCaTH(executionInfo, payload, courtListId);

        try {
            if (courtListId != null && cathSucceeded) {
                statusUpdater.markPublishSuccessful(courtListId);
            }
        } catch (Exception e) {
            logger.error("Error {} updating court list publish status to PUBLISH_SUCCESSFUL", ALERT_PATTERN, e);
        }

        CourtListPayload pdfPayload = null;
        if (listId != null && courtCentreId != null && publishDate != null) {
            try {
                pdfPayload = courtListQueryService.getCourtListPayload(
                        listId, courtCentreId, publishDate.toString(), publishDate.toString(), userId, false);
            } catch (Exception e) {
                logger.error("Error {} fetching court list payload for PDF generation", ALERT_PATTERN, e);
            }
        }

        try {
            UUID fileId = generateAndUploadPdf(executionInfo, pdfPayload);
            if (fileId != null && courtListId != null) {
                statusUpdater.markFileSuccessful(courtListId, fileId);
            }
        } catch (Exception e) {
            logger.error("Error {} generating and uploading PDF", ALERT_PATTERN, e);
            if (courtListId != null) {
                statusUpdater.markFileFailed(courtListId, e);
            }
        }

        return executionInfo().from(executionInfo)
                .withExecutionStatus(COMPLETED)
                .build();
    }

    /**
     * Attempts to publish the court list to CaTH when enabled.
     * @return true if CaTH publish completed successfully; false if disabled or an error occurred.
     */
    private boolean tryPublishToCaTH(ExecutionInfo executionInfo, CourtListPayload payload, UUID courtListId) {
        if (!cathPublishingEnabled) {
            logger.debug("CaTH publishing is disabled (CATH_PUBLISHING_ENABLED=false), skipping CaTH send");
            return false;
        }
        try {
            queryAndSendCourtListToCaTH(executionInfo, payload, courtListId);
            return true;
        } catch (Exception e) {
            logger.error("Error {} querying or sending court list to CaTH", ALERT_PATTERN, e);
            if (courtListId != null) {
                statusUpdater.markPublishFailed(courtListId, e);
            }
            return false;
        }
    }

    private void queryAndSendCourtListToCaTH(ExecutionInfo executionInfo, CourtListPayload payload, UUID courtListId) {
        if (payload == null) {
            logger.warn("Payload is null, cannot send court list to CaTH");
            return;
        }
        JsonObject jobData = executionInfo.getJobData();
        if (jobData == null) {
            return;
        }
        CourtListType listId = extractCourtListType(jobData);
        LocalDate publishDate = extractPublishDate(jobData);
        if (listId == null) {
            logger.warn("Missing listId (courtListType), cannot send court list to CaTH");
            return;
        }
        try {
            var courtListDocument = courtListQueryService.buildCourtListDocumentFromPayload(payload, listId);
            logger.info("Sending transformed court list document to CaTH endpoint");
            cathService.sendCourtListToCaTH(courtListDocument, listId, publishDate,
                    payload.getCourtIdNumeric(), payload.getIsWelsh(), courtListId);
            logger.info("Successfully sent court list document to CaTH endpoint");
        } catch (Exception e) {
            logger.error("Error {} building document or sending court list to CaTH", ALERT_PATTERN, e);
            throw new RuntimeException("Failed to send court list to CaTH: " + e.getMessage(), e);
        }
    }

    private UUID generateAndUploadPdf(ExecutionInfo executionInfo, CourtListPayload payload) {
        if (payload == null) {
            logger.warn("Payload is null, cannot generate PDF");
            return null;
        }
        JsonObject jobData = executionInfo.getJobData();
        if (jobData == null) {
            return null;
        }
        UUID courtListId = extractCourtListId(jobData);
        if (courtListId == null) {
            logger.warn("Missing courtListId for PDF generation");
            return null;
        }
        CourtListType listId = extractCourtListType(jobData);
        logger.info("Generating PDF for court list ID: {}", courtListId);
        try {
            UUID fileId = pdfHelper.generateAndUploadPdf(payload, courtListId, listId);
            logger.info("Successfully generated and uploaded PDF for court list ID: {}", courtListId);
            return fileId;
        } catch (Exception e) {
            logger.error("Error {} generating and uploading PDF for court list ID: {} after CaTH publishing", ALERT_PATTERN, courtListId, e);
            throw new RuntimeException("Error generating and uploading PDF: " + e.getMessage(), e);
        }
    }

    private CourtListType extractCourtListType(JsonObject jobData) {
        try {
            return CourtListType.valueOf(jobData.getString(JobDataConstant.COURT_LIST_TYPE, "").toUpperCase());
        } catch (Exception e) {
            logger.warn("Could not extract listId (courtListType) from JsonObject", e);
            return null;
        }
    }

    private String extractCourtCentreId(JsonObject jobData) {
        try {
            return jobData.getString(JobDataConstant.COURT_CENTRE_ID, null);
        } catch (Exception e) {
            logger.warn("Could not extract courtCentreId from JsonObject", e);
            return null;
        }
    }

    private LocalDate extractPublishDate(JsonObject jobData) {
        if (jobData == null) {
            return null;
        }
        try {
            String value = jobData.getString(JobDataConstant.PUBLISH_DATE, null);
            if (value == null || value.isBlank()) {
                return null;
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            logger.warn("Could not parse publishDate from JsonObject: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warn("Could not extract publishDate from JsonObject", e);
            return null;
        }
    }

    /**
     * Reads userId from jobData (CJSCPPUID from request). May be null if header was not sent.
     */
    private String extractUserId(JsonObject jobData) {
        if (jobData == null || !jobData.containsKey(JobDataConstant.USER_ID)) {
            return null;
        }
        try {
            String value = jobData.getString(JobDataConstant.USER_ID, null);
            return (value != null && !value.isBlank()) ? value : null;
        } catch (Exception e) {
            logger.warn("Could not extract userId from JsonObject", e);
            return null;
        }
    }

    private UUID extractCourtListId(JsonObject jobData) {
        try {
            String courtListIdStr = jobData.getString(JobDataConstant.COURT_LIST_ID, null);
            return courtListIdStr != null ? UUID.fromString(courtListIdStr) : null;
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid UUID format for courtListId: {}", jobData.getString(JobDataConstant.COURT_LIST_ID, null), e);
            return null;
        } catch (Exception e) {
            logger.warn("Could not extract courtListId from JsonObject", e);
            return null;
        }
    }
}
