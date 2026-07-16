package uk.gov.hmcts.cp.services.courtlistdownload;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.CourtListDataService;
import uk.gov.hmcts.cp.services.DocumentGeneratorClient;
import uk.gov.hmcts.cp.services.TemplateInfo;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
public class CourtListDownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(CourtListDownloadService.class);

    private static final String CONTENT_TYPE_PDF = "application/pdf";
    private static final String CONTENT_TYPE_WORD =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_FILENAME = "CourtList.pdf";
    private static final String WORD_FILENAME = "CourtList.docx";
    private static final String KEY_TEMPLATE_NAME = "templateName";

    private static final Set<CourtListType> WORD_DOWNLOAD_TYPES = EnumSet.of(
            CourtListType.USHERS_CROWN,
            CourtListType.USHERS_MAGISTRATE);

    private static final Set<CourtListType> DELEGATED_TO_LISTING_TYPES = EnumSet.of(
            CourtListType.ALPHABETICAL,
            CourtListType.JUDGE);

    private static final Set<CourtListType> DELEGATED_TO_PROGRESSION_TYPES = EnumSet.of(
            CourtListType.PUBLIC,
            CourtListType.STANDARD,
            CourtListType.BENCH);

    private static final Map<CourtListType, String> FALLBACK_TEMPLATE_BY_TYPE = new EnumMap<>(CourtListType.class);

    static {
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.PUBLIC, "PublicCourtList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.BENCH, "BenchAndStandardCourtList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.STANDARD, "BenchAndStandardCourtList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.ALPHABETICAL, "CourtList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.JUDGE, "JudgeList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.USHERS_CROWN, "UshersCrownList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.USHERS_MAGISTRATE, "UshersMagistrateList");
        FALLBACK_TEMPLATE_BY_TYPE.put(CourtListType.PRISON, "PrisonCourtList");
    }

    private static final Set<CourtListType> SUPPORTED_TYPES = FALLBACK_TEMPLATE_BY_TYPE.keySet();

    private static final Map<CourtListType, TemplateInfo> CROWN_COURT_TEMPLATES = Map.of(
            CourtListType.DRAFT,         new TemplateInfo("CrownDailyList",             "CrownDailyListWelsh"),
            CourtListType.FINAL,         new TemplateInfo("CrownDailyList",             "CrownDailyListWelsh"),
            CourtListType.ONLINE_PUBLIC, new TemplateInfo("CrownOnlinePublicCourtList", "CrownOnlinePublicCourtListWelsh"),
            CourtListType.ALPHABETICAL,  new TemplateInfo("CourtList",           "CourtListEnglishWelsh"),
            CourtListType.FIRM,          new TemplateInfo("CrownFirmList",              "CrownFirmListWelsh")
    );

    private static final Set<CourtListType> CROWN_COURT_SUPPORTED_TYPES = CROWN_COURT_TEMPLATES.keySet();

    private final CourtListDataService courtListDataService;
    private final DocumentGeneratorClient documentGeneratorClient;

    public CourtListDownloadService(final CourtListDataService courtListDataService,
                                    final DocumentGeneratorClient documentGeneratorClient) {
        this.courtListDataService = courtListDataService;
        this.documentGeneratorClient = documentGeneratorClient;
    }

    public CourtListFileResult generateCourtListDownload(final CourtListType courtListType,
                                                         final String courtCentreId,
                                                         final String courtRoomId,
                                                         final LocalDate startDate,
                                                         final LocalDate endDate,
                                                         final String cjscppuid,
                                                         final boolean restricted) {
        if (!SUPPORTED_TYPES.contains(courtListType)) {
            throw new CourtListDownloadException("Unsupported court list type for download: " + courtListType);
        }

        LOG.info("Generating court list document for type={}, courtCentreId={}, startDate={}, endDate={}, restricted={}",
                courtListType, Encode.forJava(courtCentreId), startDate, endDate, restricted);

        if (DELEGATED_TO_LISTING_TYPES.contains(courtListType)) {
            final byte[] pdf = courtListDataService.fetchCourtListPdfFromListing(
                    courtListType, courtCentreId, courtRoomId, startDate, endDate, cjscppuid, restricted);
            LOG.info("Court list document fetched from listing for type={}, courtCentreId={}, size={} bytes",
                    courtListType, Encode.forJava(courtCentreId), pdf.length);
            return new CourtListFileResult(pdf, CONTENT_TYPE_PDF, PDF_FILENAME);
        }

        if (DELEGATED_TO_PROGRESSION_TYPES.contains(courtListType)) {
            final byte[] pdf = courtListDataService.fetchCourtListPdfFromProgression(
                    courtListType, courtCentreId, courtRoomId, startDate, endDate, cjscppuid, restricted);
            LOG.info("Court list document fetched from progression for type={}, courtCentreId={}, size={} bytes",
                    courtListType, Encode.forJava(courtCentreId), pdf.length);
            return new CourtListFileResult(pdf, CONTENT_TYPE_PDF, PDF_FILENAME);
        }

        final boolean wantsWord = WORD_DOWNLOAD_TYPES.contains(courtListType);

        final String payloadJson = courtListDataService.getCourtListPayloadForDownload(
                courtListType, courtCentreId, courtRoomId, startDate, endDate, cjscppuid, restricted);

        final JsonObject payload;
        try (JsonReader reader = Json.createReader(new StringReader(payloadJson))) {
            payload = reader.readObject();
        } catch (Exception e) {
            throw new CourtListDownloadException("Failed to parse court list payload JSON: " + e.getMessage(), e);
        }

        String templateName = null;
        if (payload.containsKey(KEY_TEMPLATE_NAME) && !payload.isNull(KEY_TEMPLATE_NAME)) {
            templateName = payload.getString(KEY_TEMPLATE_NAME, null);
        }
        if (templateName == null || templateName.isBlank()) {
            templateName = FALLBACK_TEMPLATE_BY_TYPE.get(courtListType);
        }

        final byte[] content;
        try {
            content = wantsWord
                    ? documentGeneratorClient.generateWord(payload, templateName)
                    : documentGeneratorClient.generatePdf(payload, templateName);
        } catch (IOException e) {
            throw new CourtListDownloadException("Failed to render court list document: " + e.getMessage(), e);
        }

        LOG.info("Court list document generated for type={}, template={}, courtCentreId={}, format={}, size={} bytes",
                courtListType, Encode.forJava(templateName), Encode.forJava(courtCentreId), wantsWord ? "docx" : "pdf", content.length);

        return wantsWord
                ? new CourtListFileResult(content, CONTENT_TYPE_WORD, WORD_FILENAME)
                : new CourtListFileResult(content, CONTENT_TYPE_PDF, PDF_FILENAME);
    }

    public CourtListFileResult generateCrownCourtPdf(
            final CourtListType courtListType,
            final boolean isWelsh,
            final String courtCentreId,
            final String courtRoomId,
            final LocalDate startDate,
            final LocalDate endDate,
            final String cjscppuid,
            final boolean restricted) {

        if (!CROWN_COURT_SUPPORTED_TYPES.contains(courtListType)) {
            throw new CourtListDownloadException(
                    "Unsupported court list type for crown court download: " + courtListType);
        }

        TemplateInfo templates = CROWN_COURT_TEMPLATES.get(courtListType);
        String templateName = isWelsh ? templates.welshTemplate() : templates.englishTemplate();

        LOG.info("Generating crown court PDF for type={}, isWelsh={}, template={}, courtCentreId={}, startDate={}, endDate={}",
                courtListType, isWelsh, Encode.forJava(templateName), Encode.forJava(courtCentreId), startDate, endDate);

        final String payloadJson = courtListDataService.getCrownCourtDailyListPayload(
                courtListType, courtCentreId, courtRoomId, startDate, endDate, cjscppuid, restricted);

        final JsonObject payload;
        try (JsonReader reader = Json.createReader(new StringReader(payloadJson))) {
            payload = reader.readObject();
        } catch (Exception e) {
            throw new CourtListDownloadException(
                    "Failed to parse crown court payload JSON: " + e.getMessage(), e);
        }

        final byte[] content;
        try {
            content = documentGeneratorClient.generatePdf(payload, templateName);
        } catch (IOException e) {
            throw new CourtListDownloadException(
                    "Failed to render crown court PDF: " + e.getMessage(), e);
        }

        LOG.info("Crown court PDF generated for type={}, template={}, courtCentreId={}, size={} bytes",
                courtListType, Encode.forJava(templateName), Encode.forJava(courtCentreId), content.length);

        return new CourtListFileResult(content, CONTENT_TYPE_PDF, PDF_FILENAME);
    }
}
