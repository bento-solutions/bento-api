package com.bento.crm.common.exception;

/**
 * The password was right, but the Account hasn't confirmed its email address yet, so it gets no
 * session. A confirmation link has been (re)sent by the time this is thrown.
 */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
