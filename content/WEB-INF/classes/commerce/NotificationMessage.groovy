package commerce

/**
 * Channel-agnostic notification message.
 *
 * The whole point of this class is to separate WHAT a workflow wants to say from
 * HOW each channel renders it. A caller (a BPMN task-listener script) builds one
 * message describing the event in structured terms — a title, a status headline,
 * key/value fields, bullet lists, a footer — and every {@link NotificationChannel}
 * decides how to present it (Slack mrkdwn, Discord/Teams markdown, plain-text
 * email/LINE, or a structured JSON webhook payload).
 *
 * Before this model existed, each caller rendered the same message twice (once
 * with Slack's {@code *bold*}, once with Discord's {@code **bold**}) and passed
 * two pre-baked strings to dispatch — so every new channel meant touching the
 * dispatch signature and all four callers. With the structured model, adding a
 * channel is purely additive: write one adapter, register it.
 *
 * The element list is ordered; rendering walks it top to bottom. Builder methods
 * are null/empty tolerant so callers can chain optional content without guards.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.NotificationMessage}.
 */
class NotificationMessage {

    // Ordered list of content elements. Each is a Map with a 'kind' discriminator;
    // see the builder methods below for the shape of each kind.
    private final List<Map> elements = []

    static NotificationMessage create() {
        return new NotificationMessage()
    }

    // --- Builder ---------------------------------------------------------------

    /** Leading line: an icon (emoji) plus the workflow name, e.g. "📦 Inventory alert workflow". */
    NotificationMessage title(String icon, String text) {
        if (text != null && !text.trim().isEmpty()) {
            elements << [kind: 'title', icon: icon, text: text]
        }
        return this
    }

    /** Status headline under the title, e.g. "⚠ Inventory review required". */
    NotificationMessage status(String icon, String text) {
        if (text != null && !text.trim().isEmpty()) {
            elements << [kind: 'status', icon: icon, text: text]
        }
        return this
    }

    /** A "Label: value" line. Skipped when the value is null/blank. */
    NotificationMessage field(String label, Object value) {
        if (label != null && value != null && !value.toString().trim().isEmpty()) {
            elements << [kind: 'field', label: label, value: value.toString()]
        }
        return this
    }

    /** A sub-heading that groups the fields that follow it, e.g. "Variant: Small". */
    NotificationMessage heading(String text) {
        if (text != null && !text.trim().isEmpty()) {
            elements << [kind: 'heading', text: text]
        }
        return this
    }

    /** A bold heading followed by a bullet list. Skipped when the list is empty. */
    NotificationMessage bullets(String heading, List items) {
        def clean = (items ?: []).collect { it?.toString() }.findAll { it && !it.trim().isEmpty() }
        if (!clean.isEmpty()) {
            elements << [kind: 'bullets', heading: heading, items: clean]
        }
        return this
    }

    /** A bold heading followed by plain lines (no bullets), e.g. a shipping address. */
    NotificationMessage lines(String heading, List items) {
        def clean = (items ?: []).collect { it?.toString() }.findAll { it && !it.trim().isEmpty() }
        if (!clean.isEmpty()) {
            elements << [kind: 'lines', heading: heading, items: clean]
        }
        return this
    }

    /** Free-form paragraph. */
    NotificationMessage text(String text) {
        if (text != null && !text.trim().isEmpty()) {
            elements << [kind: 'text', text: text]
        }
        return this
    }

    /** Footer with the task name and assignee (or an "awaiting claim" note). */
    NotificationMessage footer(String taskName, String assignee) {
        elements << [kind: 'footer', taskName: taskName, assignee: assignee]
        return this
    }

    // --- Accessors (used by structured channels such as the generic webhook) ----

    /** The title text without its icon, or null. */
    String titleText() {
        def e = elements.find { it.kind == 'title' }
        return e?.text
    }

    /** The status headline text without its icon, or null. */
    String statusText() {
        def e = elements.find { it.kind == 'status' }
        return e?.text
    }

    /** The "field" elements as a list of [label, value] maps, in order. */
    List<Map> fields() {
        return elements.findAll { it.kind == 'field' }.collect { [label: it.label, value: it.value] }
    }

    // --- Rendering -------------------------------------------------------------

    /**
     * Render to text for a markup channel.
     *
     * @param bold   the bold delimiter ("*" for Slack mrkdwn, "**" for Discord/Teams
     *               markdown, "" for plain text such as email and LINE)
     * @param bullet the bullet marker for {@code bullets()} lists (e.g. "•", "-")
     * @param icons  whether to keep the leading emoji icons (false strips them,
     *               e.g. for an email subject line)
     */
    String render(String bold, String bullet, boolean icons) {
        def b = bold ?: ""
        def bl = bullet ?: "•"
        def sb = new StringBuilder()
        elements.each { el ->
            switch (el.kind) {
                case 'title':
                case 'status':
                    if (icons && el.icon) {
                        sb.append(el.icon).append(" ")
                    }
                    sb.append(b).append(el.text).append(b).append("\n\n")
                    break
                case 'field':
                    sb.append(el.label).append(": ").append(el.value).append("\n")
                    break
                case 'heading':
                    sb.append("\n").append(el.text).append("\n")
                    break
                case 'bullets':
                    if (el.heading) {
                        sb.append("\n").append(b).append(el.heading).append(b).append("\n")
                    }
                    el.items.each { sb.append(bl).append(" ").append(it).append("\n") }
                    break
                case 'lines':
                    if (el.heading) {
                        sb.append("\n").append(b).append(el.heading).append(b).append("\n")
                    }
                    el.items.each { sb.append(it).append("\n") }
                    break
                case 'text':
                    sb.append(el.text).append("\n")
                    break
                case 'footer':
                    sb.append("\nTask: ").append(el.taskName ?: "").append("\n")
                    sb.append(el.assignee ? "Assignee: ${el.assignee}" : "Unassigned - awaiting claim")
                    break
            }
        }
        return sb.toString()
    }

    /** Plain text rendering (no bold markup, icons kept). Used by email/LINE/webhook. */
    String plainText() {
        return render("", "•", true)
    }

    /**
     * A short one-line summary (title + status, no icons/markup). Handy for an
     * email subject or a webhook "summary" field.
     */
    String summary() {
        def parts = [titleText(), statusText()].findAll { it }
        return parts.join(" — ")
    }
}
