package uk.gov.hmcts.cp.services.sjp;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.openapi.model.SjpListType;
import uk.gov.hmcts.cp.task.JobDataConstant;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.LocalDate;
import java.util.UUID;

import static java.time.ZonedDateTime.now;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

/**
 * Queues the SJP court list publish (transform, blob upload, CaTH send) as an async job,
 * mirroring {@link uk.gov.hmcts.cp.services.CourtListTaskTriggerService} for the standard flow.
 */
@Service
public class SjpTaskTriggerService {

    private static final Logger LOG = LoggerFactory.getLogger(SjpTaskTriggerService.class);
    private static final String TASK_NAME = "SJP_PUBLISH_TASK";

    private final ExecutionService executionService;

    public SjpTaskTriggerService(ExecutionService executionService) {
        this.executionService = executionService;
    }

    public void triggerSjpPublishTask(
            final UUID sjpListId,
            final String courtIdNumeric,
            final SjpListType listType,
            final LocalDate publishDate,
            final String language,
            final String requestType,
            final String payloadJson) {

        LOG.atInfo().log("Triggering SJP publish task for sjpListId: {}, listType: {}", sjpListId, listType);

        JsonObjectBuilder jobDataBuilder = Json.createObjectBuilder()
                .add(JobDataConstant.SJP_LIST_ID, sjpListId.toString())
                .add(JobDataConstant.SJP_COURT_ID_NUMERIC, courtIdNumeric)
                .add(JobDataConstant.SJP_LIST_TYPE, listType.getValue())
                .add(JobDataConstant.SJP_PUBLISH_DATE, publishDate.toString())
                .add(JobDataConstant.SJP_PAYLOAD, payloadJson);
        if (language != null && !language.isBlank()) {
            jobDataBuilder.add(JobDataConstant.SJP_LANGUAGE, language);
        }
        if (requestType != null && !requestType.isBlank()) {
            jobDataBuilder.add(JobDataConstant.SJP_REQUEST_TYPE, requestType);
        }

        ExecutionInfo executionInfo = executionInfo()
                .withJobData(jobDataBuilder.build())
                .withAssignedTaskName(TASK_NAME)
                .withAssignedTaskStartTime(now())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withShouldRetry(false)
                .build();

        try {
            executionService.executeWith(executionInfo);
            LOG.atInfo().log("SJP publish task triggered successfully for sjpListId: {}", sjpListId);
        } catch (Exception e) {
            LOG.atError().log("Failed to execute SJP publish task via ExecutionService: {}", Encode.forJava(e.getMessage()), e);
            throw new RuntimeException("Failed to trigger SJP publish task: " + e.getMessage(), e);
        }
    }
}
