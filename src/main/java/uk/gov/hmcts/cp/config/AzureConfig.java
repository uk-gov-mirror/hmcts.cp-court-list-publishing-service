package uk.gov.hmcts.cp.config;

import static java.lang.String.format;

import com.azure.core.util.ConfigurationBuilder;
import com.azure.core.exception.AzureException;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@org.springframework.context.annotation.Profile("!integration")
public class AzureConfig {
    public static final String AZURE_CLIENT_ID = "AZURE_CLIENT_ID";
    public static final String AZURE_TENANT_ID = "AZURE_TENANT_ID";

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Value("${azure.client.id:}")
    private String clientId;

    @Value("${azure.tenant.id:}")
    private String tenantId;

    @Value("${azure.storage.account.name}")
    private String storageAccountName;

    @Bean
    public BlobContainerClient blobContainerClient() {
        validateAzureConfiguration();
        BlobServiceClient serviceClient = createBlobServiceClient();
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);

        // Try to create container if it doesn't exist, but don't fail if we lack permissions
        // The container may already exist or may be created by another process with proper permissions
        boolean containerExists = false;
        try {
            containerExists = containerClient.exists();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 403) {
                log.warn("Cannot check if Azure storage container '{}' exists due to insufficient permissions. " +
                        "Assuming container exists. Ensure the managed identity (client ID: {}) has " +
                        "'Storage Blob Data Contributor' role on storage account '{}'.",
                        containerName, clientId, storageAccountName);
                containerExists = true; // Assume it exists to continue
            } else {
                log.warn("Error checking if Azure storage container '{}' exists: {}", containerName, e.getMessage());
                throw new IllegalStateException("Failed to access Azure storage container: " + e.getMessage(), e);
            }
        }

        if (!containerExists) {
            try {
                containerClient.create();
                log.info("Created Azure storage container: {}", containerName);
            } catch (BlobStorageException e) {
                if (e.getStatusCode() == 403) {
                    log.warn("Cannot create Azure storage container '{}' due to insufficient permissions. " +
                            "Ensure the managed identity (client ID: {}) has 'Storage Blob Data Contributor' role " +
                            "on storage account '{}'. Container may already exist or will be created by another process.",
                            containerName, clientId, storageAccountName);
                } else if (e.getStatusCode() == 409) {
                    log.info("Azure storage container '{}' already exists (created by another process)", containerName);
                } else {
                    log.warn("Failed to create Azure storage container '{}': {}", containerName, e.getMessage());
                }
                // Continue anyway - container might already exist (race condition) or will be created separately
            } catch (AzureException e) {
                log.warn("Error creating Azure storage container '{}': {}", containerName, e.getMessage());
            }
        } else {
            log.info("Azure storage container '{}' already exists", containerName);
        }

        return containerClient;
    }
    
    private void validateAzureConfiguration() {
        if (!StringUtils.hasText(clientId)) {
            throw new IllegalStateException("Azure client ID is required when azure.storage.enabled=true. Set AZURE_CLIENT_ID environment variable.");
        }
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalStateException("Azure tenant ID is required when azure.storage.enabled=true. Set AZURE_TENANT_ID environment variable.");
        }
        if (!StringUtils.hasText(storageAccountName)) {
            throw new IllegalStateException("Azure storage account name is required when azure.storage.enabled=true. Set AZURE_STORAGE_ACCOUNT_NAME environment variable.");
        }
        if (!StringUtils.hasText(containerName)) {
            throw new IllegalStateException("Azure storage container name is required when azure.storage.enabled=true. Set azure.storage.container-name property.");
        }
    }

    private BlobServiceClient createBlobServiceClient() {

        final com.azure.core.util.Configuration configuration = new ConfigurationBuilder()
                .putProperty(AZURE_CLIENT_ID, clientId)
                .putProperty(AZURE_TENANT_ID, tenantId)
                .build();

        return new BlobServiceClientBuilder()
                .endpoint(format("https://%s.blob.core.windows.net/", storageAccountName))
                .credential(new DefaultAzureCredentialBuilder()
                        .tenantId(tenantId)
                        .managedIdentityClientId(clientId)
                        .configuration(configuration)
                        .build())
                .buildClient();
    }
}