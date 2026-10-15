package com.bento.crm.file.storage;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * Files in an S3-compatible bucket (AWS, OVH, Scaleway, MinIO…). Objects are written with
 * server-side encryption, and their location is kept as {@code s3:<key>}.
 */
public class S3BlobStore implements BlobStore {

    static final String PREFIX = "s3:";

    private final S3Client client;
    private final String bucket;
    private final boolean encrypt;

    public S3BlobStore(S3Client client, String bucket, boolean encrypt) {
        this.client = client;
        this.bucket = bucket;
        this.encrypt = encrypt;
    }

    public static S3BlobStore create(StorageProperties.S3 cfg) {
        var builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(cfg.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.isPathStyle()).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())));
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        return new S3BlobStore(builder.build(), cfg.getBucket(), cfg.isEncrypt());
    }

    @Override
    public String put(UUID organizationId, UUID fileId, InputStream in, long size, String contentType) throws IOException {
        String key = organizationId + "/" + fileId;
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
        if (encrypt) {
            req.serverSideEncryption(ServerSideEncryption.AES256);
        }
        client.putObject(req.build(), RequestBody.fromInputStream(in, size));
        return PREFIX + key;
    }

    @Override
    public boolean owns(String location) {
        return location != null && location.startsWith(PREFIX);
    }

    @Override
    public Resource open(String location) {
        String key = location.substring(PREFIX.length());
        try {
            InputStream in = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            return new InputStreamResource(in);
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public void delete(String location) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(location.substring(PREFIX.length())).build());
    }
}
