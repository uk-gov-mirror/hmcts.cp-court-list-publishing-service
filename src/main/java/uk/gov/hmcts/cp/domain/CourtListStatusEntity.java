package uk.gov.hmcts.cp.domain;

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

import static jakarta.persistence.EnumType.STRING;

/**
 * Shared by the standard/online-public court list flow and the SJP flow. {@code courtCentreId}
 * and {@code fileStatus}/{@code fileErrorMessage}/{@code fileUrl}/{@code fileId} are null for SJP
 * rows: SJP has no court-centre concept (it's a national list) and no PDF generation step.
 * {@code courtListType} is a plain string (not the {@code CourtListType} enum) so it can hold
 * either that enum's values (standard flow) or SJP's own list types (e.g. SJP_PUBLIC_LIST).
 * {@code payloadHash} supports SJP's content-hash dedup and is unused by the standard flow.
 */
@Getter
@Entity
@Table(name = "court_list_publish_status")
public class CourtListStatusEntity {

    @Id
    @Column(name = "court_list_id", nullable = false)
    private UUID courtListId;

    @Setter
    @Column(name = "court_centre_id")
    private UUID courtCentreId;

    @Enumerated(STRING)
    @Setter
    @Column(name = "publish_status", nullable = false)
    private Status publishStatus;

    @Enumerated(STRING)
    @Setter
    @Column(name = "file_status")
    private Status fileStatus;

    @Setter
    @Column(name = "court_list_type", nullable = false)
    private String courtListType;

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

    @Setter
    @Column(name = "payload_hash")
    private String payloadHash;

    protected CourtListStatusEntity() {
    }

    public CourtListStatusEntity(
            final UUID courtListId,
            final UUID courtCentreId,
            final Status publishStatus,
            final Status fileStatus,
            final String courtListType,
            final Instant lastUpdated) {
        this.courtListId = Objects.requireNonNull(courtListId);
        this.courtCentreId = courtCentreId;
        this.publishStatus = publishStatus;
        this.fileStatus = fileStatus;
        this.courtListType = Objects.requireNonNull(courtListType);
        this.lastUpdated = Objects.requireNonNull(lastUpdated);
    }

}
