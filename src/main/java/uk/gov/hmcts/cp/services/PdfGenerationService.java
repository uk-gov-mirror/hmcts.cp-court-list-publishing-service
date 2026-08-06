package uk.gov.hmcts.cp.services;

import com.google.common.collect.ImmutableMap;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Service for generating PDF files from payload data and uploading to Azure Blob Storage.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "azure.storage.enabled", havingValue = "true", matchIfMissing = false)
public class PdfGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfGenerationService.class);

    private static final Map<CourtListType, TemplateInfo> TEMPLATE_BY_COURT_LIST_TYPE = ImmutableMap.of(
            CourtListType.ONLINE_PUBLIC, new TemplateInfo("OnlinePublicCourtList", "OnlinePublicCourtListEnglishWelsh"),
            CourtListType.STANDARD, new TemplateInfo("BenchAndStandardCourtList", "BenchAndStandardCourtList")
    );

    private final CourtListPublisherBlobClientService blobClientService;
    private final DocumentGeneratorClient documentGeneratorClient;

    /**
     * Generates a PDF from the payload, uploads it to Azure Blob Storage as {courtListId}.pdf,
     * and returns the file ID (court list ID).
     */
    public UUID generateAndUploadPdf(JsonObject payload, UUID courtListId, CourtListType courtListType, boolean isWelsh) throws IOException {
        LOGGER.info("Generating PDF for court list ID: {}", courtListId);
        String templateName = getTemplateName(courtListType, isWelsh);

        byte[] pdfBytes;
        try {
            pdfBytes = documentGeneratorClient.generatePdf(payload, templateName);
            LOGGER.info("Successfully generated PDF for court list ID: {}, size: {} bytes",
                    courtListId, pdfBytes.length);
        } catch (Exception e) {
            LOGGER.error("Error generating PDF for court list ID: {}", courtListId, e);
            throw new IOException("Failed to generate PDF: " + e.getMessage(), e);
        }
        long pdfSize = pdfBytes.length;
        ByteArrayInputStream pdfInputStream = new ByteArrayInputStream(pdfBytes);
        blobClientService.uploadPdf(pdfInputStream, pdfSize, courtListId);
        LOGGER.info("Successfully uploaded PDF for court list ID: {}", courtListId);
        return courtListId;
    }

    /**
     * Uploads pre-rendered PDF bytes to blob storage as {courtListId}.pdf.
     */
    public UUID uploadPdfBytes(byte[] pdfBytes, UUID courtListId) {
        LOGGER.info("Uploading pre-rendered PDF for court list ID: {}, size: {} bytes", courtListId, pdfBytes.length);
        blobClientService.uploadPdf(new ByteArrayInputStream(pdfBytes), pdfBytes.length, courtListId);
        LOGGER.info("Successfully uploaded pre-rendered PDF for court list ID: {}", courtListId);
        return courtListId;
    }

    /**
     * Gets the size of the generated PDF
     */
    public long getPdfSize(ByteArrayOutputStream pdfOutputStream) {
        return pdfOutputStream.size();
    }

    /**
     * Generates PDF bytes from the given template payload and template name via the shared document generator client.
     * Exposed for use by other features (e.g. public court list) that need PDF generation without blob upload.
     */
    public byte[] generatePdfDocument(final JsonObject jsonData, final String templateIdentifier) throws IOException {
        return documentGeneratorClient.generatePdf(jsonData, templateIdentifier);
    }

    /**
     * Returns the document generator template name for the given court list type and condition.
     * When the boolean (e.g. isWelsh) is true, returns the English/Welsh template; otherwise the default template. Returns null if not mapped.
     */
    public String getTemplateName(CourtListType courtListType, boolean isWelsh) {
        TemplateInfo templateInfo = TEMPLATE_BY_COURT_LIST_TYPE.get(courtListType);
        if (templateInfo == null) {
            throw new IllegalArgumentException("No template defined for court list type: " + courtListType);
        }
        if (isWelsh) {
            return templateInfo.welshTemplate();
        }
        return templateInfo.englishTemplate();
    }
}
