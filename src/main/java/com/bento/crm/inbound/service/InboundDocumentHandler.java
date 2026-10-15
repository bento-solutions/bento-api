package com.bento.crm.inbound.service;

import com.bento.crm.inbound.model.InboundDocument;

/**
 * Handles one kind of queued document other than vendor invoices (bank statements, for one).
 * The processor gives each document to the first handler that accepts its kind.
 */
public interface InboundDocumentHandler {

    boolean handles(InboundDocument.Kind kind);

    /** Reads the document and sets its final status. Throw to have it retried. */
    void handle(InboundDocument doc, byte[] content, String contentType, String fileName);
}
