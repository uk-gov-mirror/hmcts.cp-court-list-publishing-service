package uk.gov.hmcts.cp.controllers;

import org.springframework.beans.factory.annotation.Value;
import uk.gov.hmcts.cp.cleanup.CleanupJobService;
import uk.gov.hmcts.cp.config.AppConstant;
import uk.gov.hmcts.cp.openapi.api.CourtListPublishApi;
import uk.gov.hmcts.cp.openapi.model.CourtListPublishRequest;
import uk.gov.hmcts.cp.openapi.model.CourtListPublishResponse;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.PublishStatusCleanupResponse;
import uk.gov.hmcts.cp.openapi.model.PublishCourtListRequest;
import uk.gov.hmcts.cp.openapi.model.PublishCourtListResponse;
import uk.gov.hmcts.cp.models.CourtCentreData;
import uk.gov.hmcts.cp.services.ReferenceDataService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListFileResult;
import uk.gov.hmcts.cp.services.CourtListPublishStatusService;
import uk.gov.hmcts.cp.services.sjp.SjpCourtListPublishService;
import uk.gov.hmcts.cp.services.sjp.SjpCourtListPublishService.SjpPublishResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.services.CourtListTaskTriggerService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.owasp.encoder.Encode;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
public class CourtListPublishController implements CourtListPublishApi {

    private static final Logger LOG = LoggerFactory.getLogger(CourtListPublishController.class);

    private static final String PDF_FILENAME = "CourtList.pdf";
    private static final String CONTENT_DISPOSITION_VALUE = "attachment; filename=\"" + PDF_FILENAME + "\"";
    private static final String PUBLISH_STATUS_CLEANUP_MEDIA_TYPE = "application/vnd.courtlistpublishing-service.publish-status-cleanup+json";

    private final CourtListPublishStatusService service;
    private final CourtListTaskTriggerService courtListTaskTriggerService;
    private final CourtListDownloadService courtListDownloadService;
    private final SjpCourtListPublishService sjpCourtListPublishService;
    private final CleanupJobService cleanupJobService;
    private final ReferenceDataService referenceDataService;

    @Value("${cleanup.publish-status-cleanup-days:90}")
    private int publishStatusCleanupDays;

    public CourtListPublishController(final CourtListPublishStatusService service,
                                     CourtListTaskTriggerService courtListTaskTriggerService,
                                     CourtListDownloadService courtListDownloadService,
                                     CleanupJobService cleanupJobService,
                                     SjpCourtListPublishService sjpCourtListPublishService,
                                     ReferenceDataService referenceDataService) {
        this.service = service;
        this.courtListTaskTriggerService = courtListTaskTriggerService;
        this.courtListDownloadService = courtListDownloadService;
        this.cleanupJobService = cleanupJobService;
        this.sjpCourtListPublishService = sjpCourtListPublishService;
        this.referenceDataService = referenceDataService;
    }


    @Override
    public ResponseEntity<CourtListPublishResponse> publishCourtList(
            @RequestBody final CourtListPublishRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        LOG.atInfo().log("Creating or updating court list publish status for court centre ID: {}, type: {}, startDate: {}, endDate: {}",
                request.getCourtCentreId(), request.getCourtListType(), request.getStartDate(), request.getEndDate());

        final CourtListPublishResponse response = service.createOrUpdate(
                request.getCourtCentreId(),
                request.getCourtListType(),
                request.getStartDate(),
                request.getEndDate()
        );

        String userId = getCjscppuidFromRequest();
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CJSCPPUID header is required");
        }

        // Trigger the court list publishing and PDF generation task asynchronously (userId from CJSCPPUID header)
        try {
            courtListTaskTriggerService.triggerCourtListTask(response, userId);
            LOG.atInfo().log("Court list publishing task triggered for court list ID: {}", response.getCourtListId());
        } catch (Exception e) {
            LOG.atError().log("Failed to trigger court list publishing task for court list ID: {}",
                    response.getCourtListId(), e);
        }

        return ResponseEntity.ok()
                .contentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.post+json"))
                .body(response);
    }

    @Override
    public ResponseEntity<Resource> downloadCourtList(
            String accept,
            UUID courtCentreId,
            LocalDate startDate,
            LocalDate endDate,
            CourtListType courtListType,
            UUID courtRoomId,
            Boolean restricted) {
        validateDownloadParams(courtCentreId, startDate, endDate);
        if (courtListType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courtListType is required");
        }
        if (courtListType == CourtListType.PRISON) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Prison court lists must be downloaded via " + CourtListPublishApi.PATH_DOWNLOAD_PRISON_COURT_LIST);
        }
        final String cjscppuid = requireCjscppuid();
        LOG.atInfo().log("downloadCourtList called with courtCentreId={}, startDate={}, endDate={}, courtListType={}, courtRoomId={}, restricted={}",
                courtCentreId, startDate, endDate, courtListType, courtRoomId, restricted);
        Optional<CourtCentreData> courtCentreDataOpt = referenceDataService.getCourtCenterDataByCourtCentreId(
                courtCentreId.toString(), cjscppuid);
        boolean isCrownCourt = courtCentreDataOpt.isPresent() && isCrownCourt(courtCentreDataOpt.get());
        LOG.atInfo().log("downloadCourtList courtCentreId={} isCrownCourt={}", courtCentreId, isCrownCourt);
        try {
            final CourtListFileResult result;
            if (isCrownCourt) {
                boolean isWelsh = Boolean.TRUE.equals(courtCentreDataOpt.get().getIsWelsh());
                result = courtListDownloadService.generateCrownCourtPdf(
                        courtListType, isWelsh,
                        courtCentreId.toString(),
                        courtRoomId != null ? courtRoomId.toString() : null,
                        startDate, endDate, cjscppuid, Boolean.TRUE.equals(restricted));
            } else {
                result = courtListDownloadService.generateCourtListDownload(
                        courtListType,
                        courtCentreId.toString(),
                        courtRoomId != null ? courtRoomId.toString() : null,
                        startDate, endDate, cjscppuid, Boolean.TRUE.equals(restricted));
            }
            return toFileResponse(result);
        } catch (CourtListDownloadException e) {
            LOG.warn("Court list download error: {}", Encode.forJava(e.getMessage()));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /**
     * Dedicated prison court list download. Access to this path is restricted to prison-list roles
     * by the Drools ACL ({@code acl/court-list-publishing-rules.drl}) which keys on the request path,
     * preserving progression's prison-vs-standard authorisation split at the publishing tier.
     */
    @Override
    public ResponseEntity<Resource> downloadPrisonCourtList(
            final String accept,
            final UUID courtCentreId,
            final LocalDate startDate,
            final LocalDate endDate,
            final UUID courtRoomId) {
        validateDownloadParams(courtCentreId, startDate, endDate);
        final String cjscppuid = requireCjscppuid();
        LOG.atInfo().log("downloadPrisonCourtList called with courtCentreId={}, startDate={}, endDate={}, courtRoomId={}",
                courtCentreId, startDate, endDate, courtRoomId);
        try {
            // Prison lists are never restricted-filtered and always render locally via the prison template.
            final CourtListFileResult result = courtListDownloadService.generateCourtListDownload(
                    CourtListType.PRISON,
                    courtCentreId.toString(),
                    courtRoomId != null ? courtRoomId.toString() : null,
                    startDate, endDate, cjscppuid, false);
            return toFileResponse(result);
        } catch (CourtListDownloadException e) {
            LOG.warn("Prison court list download error: {}", Encode.forJava(e.getMessage()));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<PublishCourtListResponse> publishSjpCourtList(
            @RequestBody @Valid final PublishCourtListRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (request.getListType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "listType is required");
        }
        if (request.getListPayload() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "listPayload is required");
        }

        LOG.atInfo().log("SJP court list publish request for listType: {}", request.getListType());

        SjpPublishResult result = sjpCourtListPublishService.publishSjpCourtList(
                request.getListType(),
                request.getLanguage(),
                request.getRequestType(),
                request.getListPayload());

        PublishCourtListResponse response = new PublishCourtListResponse(
                result.getStatus(),
                request.getListType(),
                result.getMessage());
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "vnd.courtlistpublishing-service.sjp.post+json"))
                .body(response);
    }

    @SuppressWarnings("unused") // Method is used by Spring's request mapping
    public ResponseEntity<List<CourtListPublishResponse>> findCourtListPublishStatus(
            @RequestParam(required = false) final UUID courtListId,
            @RequestParam(required = false) final UUID courtCentreId,
            @RequestParam(required = false) final LocalDate publishDate,
            @RequestParam(required = false) final CourtListType courtListType) {
        LOG.atInfo().log("Fetching court list publish statuses - courtListId: {}, courtCentreId: {}, publishDate: {}, courtListType: {}",
                courtListId, courtCentreId, publishDate, courtListType);
        final List<CourtListPublishResponse> responses = service.findPublishStatus(
                courtListId, courtCentreId, publishDate, courtListType);
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "vnd.courtlistpublishing-service.publish.get+json"))
                .body(responses);
    }

    @Override
    public ResponseEntity<PublishStatusCleanupResponse> publishStatusCleanup() {
        try {
            cleanupJobService.cleanupOldData(publishStatusCleanupDays);
            return ResponseEntity.accepted()
                    .contentType(MediaType.parseMediaType(PUBLISH_STATUS_CLEANUP_MEDIA_TYPE))
                    .body(PublishStatusCleanupResponse.builder().success(true).build());
        } catch (Exception e) {
            LOG.error("Publish status cleanup failed", e);
            return ResponseEntity.accepted()
                    .contentType(MediaType.parseMediaType(PUBLISH_STATUS_CLEANUP_MEDIA_TYPE))
                    .body(PublishStatusCleanupResponse.builder().success(false).build());
        }
    }

    private static String getCjscppuidFromRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getHeader(AppConstant.CJSCPPUID);
        }
        return null;
    }

    /** Validates the query parameters common to every court list download. */
    private static void validateDownloadParams(final UUID courtCentreId, final LocalDate startDate, final LocalDate endDate) {
        if (courtCentreId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courtCentreId is required");
        }
        if (startDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate is required (format: yyyy-MM-dd)");
        }
        if (endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate is required (format: yyyy-MM-dd)");
        }
        if (endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must be on or after startDate");
        }
    }

    /** Returns the caller's CJSCPPUID, or throws 400 when the header is absent. */
    private static String requireCjscppuid() {
        final String cjscppuid = getCjscppuidFromRequest();
        if (cjscppuid == null || cjscppuid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CJSCPPUID header is required");
        }
        return cjscppuid;
    }

    /** Wraps a generated court list file in a download response (attachment + content type). */
    private static ResponseEntity<Resource> toFileResponse(final CourtListFileResult result) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(result.contentType()));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"");
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(result.content()));
    }

    private static boolean isCrownCourt(CourtCentreData courtCentreData) {
        String oucodeL1Code = courtCentreData.getOucodeL1Code();
        return "C".equalsIgnoreCase(oucodeL1Code);
    }
}

