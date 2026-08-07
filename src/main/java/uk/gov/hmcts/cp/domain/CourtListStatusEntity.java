package uk.gov.hmcts.cp.domain;

import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import static jakarta.persistence.EnumType.STRING;

/**
 * Shared by the standard/online-public court list flow and the SJP flow. A repeat publish for
 * the same key overwrites the existing row (via {@code lastUpdated}) — no content dedup.
 */
@Getter
@Entity
@Table(name = "court_list_publish_status", check = {
        @CheckConstraint(name = "ck_court_centre_id_required_for_non_sjp",
                constraint = "court_centre_id IS NOT NULL OR starts_with(court_list_type, 'SJP_')"),
        @CheckConstraint(name = "ck_file_status_required_for_non_sjp",
                constraint = "file_status IS NOT NULL OR starts_with(court_list_type, 'SJP_')")
})
public class CourtListStatusEntity {

    @Id
    @Column(name = "court_list_id", nullable = false)
    private UUID courtListId;

    /** NULL for SJP (national, no court centre); required otherwise. */
    @Setter
    @Column(name = "court_centre_id")
    private UUID courtCentreId;

    @Enumerated(STRING)
    @Setter
    @Column(name = "publish_status", nullable = false)
    private Status publishStatus;

    /** NULL for SJP (no file is produced). */
    @Enumerated(STRING)
    @Setter
    @Column(name = "file_status")
    private Status fileStatus;

    /** For SJP rows, one of the fused SJP_* values (see SjpStatusListTypeMapper), not the CaTH wire type. */
    @Enumerated(STRING)
    @Setter
    @Column(name = "court_list_type", nullable = false)
    private CourtListType courtListType;

    @Setter
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @Setter
    @Column(name = "file_url")
    private String fileUrl;

    @Setter
    @Column(name = "file_id")
    private UUID fileId;

    @Setter
    @Column(name = "publish_error_message")
    private String publishErrorMessage;

    @Setter
    @Column(name = "file_error_message")
    private String fileErrorMessage;

    @Setter
    @Column(name = "publish_date", nullable = false)
    private LocalDate publishDate;

    @Setter
    @Column(name = "publish_count", nullable = false)
    private int publishCount;

    protected CourtListStatusEntity() {
    }

    public CourtListStatusEntity(
            final UUID courtListId,
            final UUID courtCentreId,
            final Status publishStatus,
            final Status fileStatus,
            final CourtListType courtListType,
            final Instant lastUpdated) {
        this.courtListId = Objects.requireNonNull(courtListId);
        this.courtCentreId = courtCentreId; // null for SJP - see field javadoc
        this.publishStatus = publishStatus;
        this.fileStatus = fileStatus;       // null for SJP - see field javadoc
        this.courtListType = Objects.requireNonNull(courtListType);
        this.lastUpdated = Objects.requireNonNull(lastUpdated);
    }

}
