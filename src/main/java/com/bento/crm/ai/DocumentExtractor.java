package com.bento.crm.ai;

/** Reads a business document (PDF or image) into structured fields. */
public interface DocumentExtractor {

    /** Whether extraction can run at all (an AI key is configured). */
    boolean available();

    DocumentExtraction extract(byte[] content, String contentType, String fileName);
}
