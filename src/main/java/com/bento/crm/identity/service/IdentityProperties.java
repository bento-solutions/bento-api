package com.bento.crm.identity.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.identity")
@Data
public class IdentityProperties {

    /**
     * Whether an Account must confirm its email address before it gets a session. It needs
     * working outgoing mail, so it stays off until production can send email: turned on without
     * mail, nobody new could sign in and everyone else would be locked out at their next sign-in.
     */
    private boolean requireVerifiedEmail = false;
}
