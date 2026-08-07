package uk.gov.hmcts.cp.domain.sjp;

import static jakarta.persistence.EnumType.STRING;

import uk.gov.hmcts.cp.openapi.model.Status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracks SJP publish status keyed on (courtIdNumeric, listType, publishDate), mirroring
 * {@link uk.gov.hmcts.cp.domain.CourtListStatusEntity}'s dedup-by-lookup pattern: a repeat
 * request for the same court/list-type/date reuses the same {@code sjpListId} row instead of
 * minting a new one. {@code payloadHash} additionally allows the async publish task to skip
 * re-uploading/re-publishing identical content.
 */
@Getter
@Entity
@Table(name = "sjp_publish_status")
public class SjpPublishStatusEntity {

    @Id
    @Column(name = "sjp_list_id", nullable = false)
    private UUID sjpListId;

    @Setter
    @Column(name = "court_id_numeric", nullable = false)
    private String courtIdNumeric;

    @Setter
    @Column(name = "list_type", nullable = false)
    private String listType;

    @Setter
    @Column(name = "publish_date", nullable = false)
    private LocalDate publishDate;

    @Enumerated(STRING)
    @Setter
    @Column(name = "publish_status", nullable = false)
    private Status publishStatus;

    @Setter
    @Column(name = "payload_hash")
    private String payloadHash;

    @Setter
    @Column(name = "publish_error_message")
    private String publishErrorMessage;

    @Setter
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    protected SjpPublishStatusEntity() {
    }

    public SjpPublishStatusEntity(
            final UUID sjpListId,
            final String courtIdNumeric,
            final String listType,
            final LocalDate publishDate,
            final Status publishStatus,
            final Instant lastUpdated) {
        this.sjpListId = Objects.requireNonNull(sjpListId);
        this.courtIdNumeric = Objects.requireNonNull(courtIdNumeric);
        this.listType = Objects.requireNonNull(listType);
        this.publishDate = Objects.requireNonNull(publishDate);
        this.publishStatus = Objects.requireNonNull(publishStatus);
        this.lastUpdated = Objects.requireNonNull(lastUpdated);
    }
}
