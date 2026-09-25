package com.bento.crm.whatsapp.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text rules for messages sent from a linked personal number, where WhatsApp's spam detection
 * looks at content as well as volume: links in unsolicited messages, and the same words sent to
 * many people, are two of its strongest signals.
 *
 * <p>Campaign texts can therefore be personalized: {@code {{first_name}}}, {@code {{name}}} and
 * {@code {{company}}} take the contact's details, and {@code {Bonjour|Salut|Hello}} picks one of
 * the variants per contact (the same one every time for a given contact).
 */
public final class WaMessageText {

    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.)\\S+"
                    + "|(?<![@\\w.-])[a-z0-9][a-z0-9-]*(\\.[a-z0-9-]+)*\\."
                    + "(com|net|org|ma|fr|io|co|me|ly|gl|gg|to|app|link|site|online|shop|store|info|biz|xyz)"
                    + "(/\\S*)?(?![\\w-])");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(first_name|name|company)\\s*}}",
            Pattern.CASE_INSENSITIVE);

    /** {@code {a|b|c}}: at least one bar, no nested braces (so {@code {{name}}} never matches). */
    private static final Pattern VARIANTS = Pattern.compile("\\{([^{}|]*(?:\\|[^{}|]*)+)}");

    /** Stands in for a placeholder with no value until the spacing around it is fixed. */
    private static final String EMPTY = "\u0000";

    private WaMessageText() {
    }

    /** Whether the text holds a URL or a bare web address (e-mail addresses do not count). */
    public static boolean containsLink(String text) {
        return text != null && LINK.matcher(text).find();
    }

    /** Whether the text changes per contact: a placeholder or a set of variants. */
    public static boolean isPersonalized(String text) {
        return text != null && (PLACEHOLDER.matcher(text).find() || VARIANTS.matcher(text).find());
    }

    /**
     * The text for one contact.
     *
     * @param seed stable per contact (e.g. derived from the recipient id), so a retry or a
     *             preview renders the same variants
     */
    public static String render(String template, String name, String company, long seed) {
        if (template == null) {
            return null;
        }
        String fullName = name == null ? "" : name.strip();
        String firstName = fullName.isEmpty() ? "" : fullName.split("\\s+")[0];
        Matcher placeholders = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (placeholders.find()) {
            String value = switch (placeholders.group(1).toLowerCase()) {
                case "first_name" -> firstName;
                case "name" -> fullName;
                default -> company == null ? "" : company.strip();
            };
            placeholders.appendReplacement(out, Matcher.quoteReplacement(value.isEmpty() ? EMPTY : value));
        }
        placeholders.appendTail(out);

        Matcher variants = VARIANTS.matcher(out.toString());
        StringBuilder chosen = new StringBuilder();
        int group = 0;
        while (variants.find()) {
            String[] options = variants.group(1).split("\\|", -1);
            int pick = Math.floorMod(Long.hashCode(seed * 31 + group++), options.length);
            variants.appendReplacement(chosen, Matcher.quoteReplacement(options[pick]));
        }
        variants.appendTail(chosen);
        // A missing name must not leave "Bonjour ," behind; other spacing (French "rentrée :") stays.
        return chosen.toString()
                .replaceAll("[ \\t]*" + EMPTY + "(?=[,.!?;:])", "")
                .replaceAll("[ \\t]*" + EMPTY + "[ \\t]*", " ")
                .strip();
    }
}
