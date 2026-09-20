package ai.genesisbrands.service;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Abstracts file storage — Azure Blob in production, local ./data/blobs/ in dev.
 * Switches automatically based on whether AZURE_STORAGE_ACCOUNT is configured.
 */
@Service
public class BlobStorageService {

    @Value("${azure.storage.account-name:}")
    private String accountName;

    @Value("${azure.storage.account-key:}")
    private String accountKey;

    @Value("${azure.storage.container:knowledge}")
    private String containerName;

    private BlobContainerClient containerClient;
    private Path localRoot;
    private boolean useAzure;

    @PostConstruct
    void init() throws IOException {
        useAzure = accountName != null && !accountName.isBlank()
                && accountKey  != null && !accountKey.isBlank();

        if (useAzure) {
            String connectionString = String.format(
                "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                accountName, accountKey);
            BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
            containerClient = serviceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) containerClient.create();
        } else {
            localRoot = Paths.get("data/blobs");
            Files.createDirectories(localRoot);
        }
    }

    public void upload(String blobPath, byte[] data) throws IOException {
        if (useAzure) {
            containerClient.getBlobClient(blobPath)
                .upload(new ByteArrayInputStream(data), data.length, true);
        } else {
            Path target = localRoot.resolve(blobPath);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        }
    }

    public byte[] download(String blobPath) throws IOException {
        if (useAzure) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                containerClient.getBlobClient(blobPath).downloadStream(out);
            } catch (BlobStorageException e) {
                throw new IOException("Blob not found: " + blobPath, e);
            }
            return out.toByteArray();
        } else {
            return Files.readAllBytes(localRoot.resolve(blobPath));
        }
    }

    public void delete(String blobPath) {
        if (useAzure) {
            containerClient.getBlobClient(blobPath).deleteIfExists();
        } else {
            try { Files.deleteIfExists(localRoot.resolve(blobPath)); } catch (IOException ignored) {}
        }
    }
}
