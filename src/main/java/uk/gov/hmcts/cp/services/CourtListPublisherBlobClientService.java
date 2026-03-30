package uk.gov.hmcts.cp.services;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Uploads PDF files to Azure Blob Storage. Files are stored with name {fileId}.pdf
 * (under court-lists/). No SAS URL is generated; callers reference the file by fileId only.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "azure.storage.enabled", havingValue = "true", matchIfMissing = false)
public class CourtListPublisherBlobClientService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CourtListPublisherBlobClientService.class);
    private static final String PDF_EXTENSION = ".pdf";

    private final BlobContainerClient blobContainerClient;

    /**
     * Uploads a PDF to blob storage with blob name {fileId}.pdf.
     *
     * @param fileInputStream PDF content
     * @param fileSize        size in bytes
     * @param fileId          identifier for the file (e.g. court list ID); used as blob name (with .pdf)
     */
    public void uploadPdf(InputStream fileInputStream, long fileSize, UUID fileId) {
        String blobName = buildPdfBlobName(fileId);
        try {
            LOGGER.info("Uploading PDF {} to container {}", blobName, blobContainerClient.getBlobContainerName());
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            BlobHttpHeaders headers = new BlobHttpHeaders().setContentType("application/pdf");
            blobClient.upload(fileInputStream, fileSize, true);
            blobClient.setHttpHeaders(headers);
            LOGGER.info("Successfully uploaded PDF: {}", blobName);
        } catch (Exception e) {
            LOGGER.error("Error uploading PDF {} to Azure Blob Storage", blobName, e);
            throw new RuntimeException(
                "Azure storage error while uploading PDF: " + blobName + ". " + e.getMessage(), e);
        }
    }

    /**
     * Downloads the full PDF blob named {fileId}.pdf as a byte array in a single shot.
     * Returns empty if the blob does not exist.
     */
    public Optional<byte[]> downloadPdf(UUID fileId) {
        return openPdfStream(fileId)
                .map(stream -> {
                    try (InputStream in = stream) {
                        return in.readAllBytes();
                    } catch (Exception e) {
                        LOGGER.error("Error reading PDF content for {}", buildPdfBlobName(fileId), e);
                        throw new RuntimeException(
                                "Azure storage error while reading PDF: " + buildPdfBlobName(fileId) + ". " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Opens a streaming input stream for the PDF blob named {fileId}.pdf.
     * Caller must close the stream when done. Returns empty if the blob does not exist.
     */
    public Optional<InputStream> openPdfStream(UUID fileId) {
        String blobName = buildPdfBlobName(fileId);
        try {
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            if (!blobClient.exists()) {
                return Optional.empty();
            }
            return Optional.of(blobClient.openInputStream());
        } catch (Exception e) {
            LOGGER.error("Error opening download stream for PDF {}", blobName, e);
            throw new RuntimeException(
                "Azure storage error while opening PDF stream: " + blobName + ". " + e.getMessage(), e);
        }
    }

    /**
     * Blob name for a court list PDF.
     */
    public static String buildPdfBlobName(UUID fileId) {
        return fileId + PDF_EXTENSION;
    }

}
