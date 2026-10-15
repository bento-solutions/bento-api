package com.bento.crm.file.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code app.storage}: where new files are written. */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** {@code local} (the server's disk) or {@code s3}. */
    private String type = "local";

    private S3 s3 = new S3();

    @Data
    public static class S3 {
        private String endpoint;
        private String region = "eu-west-3";
        private String bucket;
        private String accessKey;
        private String secretKey;
        /** Path-style URLs, which MinIO and most non-AWS providers need. */
        private boolean pathStyle = true;
        /** Server-side encryption (SSE-S3) on every object. */
        private boolean encrypt = true;
    }
}
