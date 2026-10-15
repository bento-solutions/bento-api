package com.bento.crm.file.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Where file bytes live. {@link com.bento.crm.file.model.StoredFile#getStoragePath()} keeps the
 * location a store returned, so files written by one backend stay readable after switching to
 * another (see {@link BlobStores}).
 */
public interface BlobStore {

    /** Writes the bytes and returns the location to keep on the file row. */
    String put(UUID organizationId, UUID fileId, InputStream in, long size, String contentType) throws IOException;

    /** Whether this store wrote the given location. */
    boolean owns(String location);

    /** The bytes at a location this store wrote, or null when they are gone. */
    Resource open(String location);

    void delete(String location) throws IOException;
}
