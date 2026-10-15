package com.bento.crm.inbound.mail;

import com.bento.crm.inbound.service.InboundIntakeService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * For servers without an inbound-email provider: reads unseen messages from one catch-all IMAP
 * mailbox (e.g. *@in.example.com, or docs+token@example.com with plus addressing) and files them
 * like webhook emails. Messages are marked seen once handled, so each is read once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.inbound.imap.enabled", havingValue = "true")
public class ImapPoller {

    private static final int MAX_PER_RUN = 50;

    private final InboundIntakeService intake;

    @Value("${app.inbound.imap.host}")
    private String host;
    @Value("${app.inbound.imap.port:993}")
    private int port;
    @Value("${app.inbound.imap.username}")
    private String username;
    @Value("${app.inbound.imap.password}")
    private String password;
    @Value("${app.inbound.imap.folder:INBOX}")
    private String folderName;

    @Scheduled(fixedDelayString = "${app.inbound.imap.poll-ms:60000}", initialDelay = 30000)
    public void poll() {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", "20000");
        p.put("mail.imaps.timeout", "60000");
        try (Store store = Session.getInstance(p).getStore("imaps")) {
            store.connect(host, port, username, password);
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            try {
                Message[] unseen = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                int n = 0;
                for (Message m : unseen) {
                    if (n++ >= MAX_PER_RUN) {
                        break;
                    }
                    try {
                        intake.ingest(MimeEmailParser.parse((MimeMessage) m));
                    } catch (Exception e) {
                        log.warn("Could not read inbound email {}: {}", m.getMessageNumber(), e.getMessage());
                    }
                    // Seen even on failure: one broken message must not block the mailbox.
                    m.setFlag(Flags.Flag.SEEN, true);
                }
            } finally {
                folder.close(false);
            }
        } catch (Exception e) {
            log.warn("IMAP poll of {} failed: {}", host, e.getMessage());
        }
    }
}
