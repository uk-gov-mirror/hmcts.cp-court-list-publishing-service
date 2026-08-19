package uk.gov.hmcts.cp.domain;

import static jakarta.persistence.EnumType.STRING;

import uk.gov.hmcts.cp.openapi.model.CourtListType;
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
import org.hibernate.annotations.Check;
import lombok.Getter;
import lombok.Setter;

@Getter
@Entity
@Table(name = "court_list_publish_status")
@Check(name = "ck_court_centre_id_required_for_non_sjp",
        constraints = "court_centre_id IS NOT NULL OR starts_with(court_list_type, 'SJP_')")
@Check(name = "ck_file_status_required_for_non_sjp",
        constraints = "file_status IS NOT NULL OR starts_with(court_list_type, 'SJP_')")
public class CourtListStatusEntity {

    @Id
    @Column(name = "court_list_id", nullable = false)
    private UUID courtListId;

    /** NULL for SJP: SJP is national and has no court centre. Required for every other list type. */
    @Setter
    @Column(name = "court_centre_id")
    private UUID courtCentreId;

    @Enumerated(STRING)
    @Setter
    @Column(name = "publish_status", nullable = false)
    private Status publishStatus;

    /** NULL for SJP: the SJP flow publishes JSON to CaTH and produces no file. */
    @Enumerated(STRING)
    @Setter
    @Column(name = "file_status")
    private Status fileStatus;

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

