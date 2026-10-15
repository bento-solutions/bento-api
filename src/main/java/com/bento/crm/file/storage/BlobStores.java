package com.bento.crm.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes to the configured store and reads from whichever store wrote a file, so switching
 * {@code app.storage.type} from local to s3 keeps older files readable.
 */
@Slf4j
@Component
public class BlobStores {

    private final BlobStore writer;
    private final List<BlobStore> readers = new ArrayList<>();

    @org.springframework.beans.factory.annotation.Autowired
    public BlobStores(LocalBlobStore local, StorageProperties props) {
        BlobStore s3 = null;
        if ("s3".equalsIgnoreCase(props.getType())) {
            if (props.getS3().getBucket() == null || props.getS3().getBucket().isBlank()) {
                throw new IllegalStateException("app.storage.type=s3 needs app.storage.s3.bucket");
            }
            s3 = S3BlobStore.create(props.getS3());
            log.info("Storing new files in S3 bucket {}", props.getS3().getBucket());
        }
        this.writer = s3 != null ? s3 : local;
        if (s3 != null) {
            readers.add(s3);
        }
        readers.add(local);
    }

    /** For tests and alternative wiring. */
    public BlobStores(BlobStore writer, List<BlobStore> readers) {
        this.writer = writer;
        this.readers.addAll(readers);
    }

    public String put(UUID organizationId, UUID fileId, InputStream in, long size, String contentType) throws IOException {
        return writer.put(organizationId, fileId, in, size, contentType);
    }

    public Resource open(String location) {
        BlobStore store = storeFor(location);
        return store == null ? null : store.open(location);
    }

    public void delete(String location) throws IOException {
        BlobStore store = storeFor(location);
        if (store != null) {
            store.delete(location);
        }
    }

    private BlobStore storeFor(String location) {
        for (BlobStore store : readers) {
            if (store.owns(location)) {
                return store;
            }
        }
        return null;
    }
}
