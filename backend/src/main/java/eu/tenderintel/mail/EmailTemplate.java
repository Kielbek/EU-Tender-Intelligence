package eu.tenderintel.mail;

/** Transactional e-mail templates; texts live in {@code messages_<locale>.properties} under {@code mail.<key>.*}. */
public enum EmailTemplate {
    VERIFY_EMAIL("verify"),
    ACCOUNT_ALREADY_EXISTS("exists"),
    PASSWORD_RESET("reset"),
    PASSWORD_CHANGED("passwordChanged"),
    ORGANIZATION_INVITATION("invitation"),
    BILLING_PAYMENT_FAILED("paymentFailed"),
    NOTIFICATION_DIGEST("digest");

    private final String key;

    EmailTemplate(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
