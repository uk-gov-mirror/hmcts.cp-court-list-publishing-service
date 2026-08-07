package uk.gov.hmcts.cp.services;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.Status;
import uk.gov.hmcts.cp.repositories.CourtListStatusRepository;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared publish/file status bookkeeping for {@code court_list_publish_status}, used by both
 * {@code CourtListPublishAndPDFGenerationTask} (publish + file) and {@code SjpPublishTask}
 * (publish only). A success clears the corresponding error message; a failure records the
 * full stack trace.
 */
@Component
@RequiredArgsConstructor
public class CourtListStatusUpdater {

    private static final Logger logger = LoggerFactory.getLogger(CourtListStatusUpdater.class);

    private final CourtListStatusRepository repository;

    public void markPublishSuccessful(UUID courtListId) {
        withEntity(courtListId, entity -> {
            entity.setPublishStatus(Status.SUCCESSFUL);
            entity.setPublishErrorMessage(null);
        });
    }

    public void markPublishFailed(UUID courtListId, Exception e) {
        withEntity(courtListId, entity -> {
            entity.setPublishStatus(Status.FAILED);
            entity.setPublishErrorMessage(buildErrorMessage(e));
        });
    }

    public void markFileSuccessful(UUID courtListId, UUID fileId) {
        withEntity(courtListId, entity -> {
            entity.setFileId(fileId);
            entity.setFileStatus(Status.SUCCESSFUL);
            entity.setFileErrorMessage(null);
            entity.setPublishCount(entity.getPublishCount() + 1);
        });
    }

    public void markFileFailed(UUID courtListId, Exception e) {
        withEntity(courtListId, entity -> {
            entity.setFileStatus(Status.FAILED);
            entity.setFileErrorMessage(buildErrorMessage(e));
        });
    }

    private void withEntity(UUID courtListId, Consumer<CourtListStatusEntity> mutator) {
        CourtListStatusEntity entity = repository.getByCourtListId(courtListId);
        if (entity == null) {
            logger.warn("No court list publish status record found for court list ID: {}", courtListId);
            return;
        }
        mutator.accept(entity);
        entity.setLastUpdated(Instant.now());
        repository.save(entity);
    }

    private static String buildErrorMessage(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
