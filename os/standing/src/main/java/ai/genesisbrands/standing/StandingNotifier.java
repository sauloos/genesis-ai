package ai.genesisbrands.standing;

import ai.genesisbrands.model.ClientUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Emails a client when a run leaves something in their inbox. Off until the app configures
 * outbound mail: an SMTP server ({@code spring.mail.host}, e.g. Azure Communication Services'
 * SMTP endpoint) and a sender address ({@code genesis.standing.notify.from}). Until then it only
 * logs, so nothing changes for a deployment that has not set it up.
 */
@Slf4j
@Service
public class StandingNotifier {

    private final ObjectProvider<JavaMailSender> mail;
    private final StandingCatalog catalog;
    private final String from;
    private final String inboxUrl;

    public StandingNotifier(ObjectProvider<JavaMailSender> mail, StandingCatalog catalog,
                            @Value("${genesis.standing.notify.from:}") String from,
                            @Value("${genesis.standing.notify.inbox-url:}") String inboxUrl) {
        this.mail = mail;
        this.catalog = catalog;
        this.from = from;
        this.inboxUrl = inboxUrl;
    }

    public boolean enabled() {
        return mail.getIfAvailable() != null && !from.isBlank();
    }

    /** Never throws: a failed email must not fail the run that produced the items. */
    public void itemsArrived(ClientUser client, StandingSubscription sub, int count) {
        if (count <= 0 || Boolean.FALSE.equals(sub.getNotifyByEmail())) return;
        if (client.getEmail() == null || client.getEmail().isBlank()) return;
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null || from.isBlank()) {
            log.debug("Email notifications are not configured; {} new item(s) for client {}", count, client.getId());
            return;
        }
        try {
            sender.send(message(client, catalog.displayName(sub.getAgentId()), count));
        } catch (RuntimeException e) {
            log.warn("Could not email client {} about {} new item(s): {}", client.getId(), count, e.getMessage());
        }
    }

    SimpleMailMessage message(ClientUser client, String agentName, int count) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(client.getEmail());
        String things = count == 1 ? "1 new item is" : count + " new items are";
        m.setSubject(agentName + ": " + things + " waiting for you");
        StringBuilder body = new StringBuilder();
        body.append("Hello").append(client.getName() == null || client.getName().isBlank() ? "" : " " + client.getName().trim()).append(",\n\n");
        body.append(agentName).append(" has finished a run. ").append(things.substring(0, 1).toUpperCase())
            .append(things.substring(1)).append(" waiting in your inbox for your approval.\n\n");
        if (!inboxUrl.isBlank()) body.append("Review them here: ").append(inboxUrl).append("\n\n");
        body.append("You can turn these emails off in the agent's settings on your dashboard.\n");
        m.setText(body.toString());
        return m;
    }
}
