package uk.gov.hmcts.cp.controllers;

import uk.gov.hmcts.cp.config.AppConstant;
import uk.gov.hmcts.cp.openapi.api.CourtListPublishApi;
import uk.gov.hmcts.cp.openapi.model.CourtListDownloadRequest;
import uk.gov.hmcts.cp.openapi.model.CourtListPublishRequest;
import uk.gov.hmcts.cp.openapi.model.CourtListPublishResponse;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.PublishCourtListRequest;
import uk.gov.hmcts.cp.openapi.model.PublishCourtListResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.services.CourtListTaskTriggerService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.EnumSet;
import java.util.Set;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class CourtListPublishController implements CourtListPublishApi {

    private static final Logger LOG = LoggerFactory.getLogger(CourtListPublishController.class);


    private final CourtListPublishStatusService service;
    private final CourtListTaskTriggerService courtListTaskTriggerService;
    private final CourtListDownloadService courtListDownloadService;
    private final SjpCourtListPublishService sjpCourtListPublishService;

    public CourtListPublishController(final CourtListPublishStatusService service,
                                     CourtListTaskTriggerService courtListTaskTriggerService,
                                     CourtListDownloadService courtListDownloadService,
                                     SjpCourtListPublishService sjpCourtListPublishService) {
        this.service = service;
        this.courtListTaskTriggerService = courtListTaskTriggerService;
        this.courtListDownloadService = courtListDownloadService;
        this.sjpCourtListPublishService = sjpCourtListPublishService;
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
    public ResponseEntity<Resource> downloadCourtList(@RequestBody CourtListDownloadRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (request.getCourtCentreId() == null || request.getCourtCentreId().toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courtCentreId is required");
        }
        if (request.getStartDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate is required (format: yyyy-MM-dd)");
        }
        if (request.getEndDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate is required (format: yyyy-MM-dd)");
        }
        if (request.getCourtListType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courtListType is required");
        }
        if (!isSupportedCourtListTypeForDownload(request.getCourtListType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Download supported for PUBLIC, BENCH, ALPHABETICAL, USHERS_CROWN, USHERS_MAGISTRATE only. Got: "
                    + request.getCourtListType());
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must be on or after startDate");
        }
        try {
            CourtListFileResult result = courtListDownloadService.generateCourtListDownload(
                    request.getCourtListType(),
                    request.getCourtCentreId().toString(),
                    request.getStartDate(),
                    request.getEndDate());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(result.contentType()));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"");
            return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(result.content()));
        } catch (CourtListDownloadException e) {
            LOG.warn("Court list download error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<PublishCourtListResponse> publishSjpCourtList(
            @RequestBody final PublishCourtListRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (request.getListType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "listType is required");
        }

        LOG.atInfo().log("SJP court list publish request for listType: {}", request.getListType());

        SjpPublishResult result = sjpCourtListPublishService.publishSjpCourtList(
                request.getListType().getValue(),
                null,
                null,
                null);

        PublishCourtListResponse response = new PublishCourtListResponse(
                result.getStatus(),
                request.getListType(),
                result.getMessage());
        return ResponseEntity.ok(response);
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

    private static final Set<CourtListType> SUPPORTED_DOWNLOAD_COURT_LIST_TYPES = EnumSet.of(
            CourtListType.PUBLIC,
            CourtListType.BENCH,
            CourtListType.ALPHABETICAL,
            CourtListType.USHERS_CROWN,
            CourtListType.USHERS_MAGISTRATE);

    private static boolean isSupportedCourtListTypeForDownload(CourtListType courtListType) {
        return courtListType != null && SUPPORTED_DOWNLOAD_COURT_LIST_TYPES.contains(courtListType);
    }

    private static String getCjscppuidFromRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getHeader(AppConstant.CJSCPPUID);
        }
        return null;
    }
}

