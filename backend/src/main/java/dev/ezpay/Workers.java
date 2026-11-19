package dev.ezpay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Workers {
    private static final Logger log = LoggerFactory.getLogger(Workers.class);
    private final Accounts accounts;
    private final Payments payments;
    private final Activity activity;

    Workers(Accounts accounts, Payments payments, Activity activity) {
        this.accounts = accounts;
        this.payments = payments;
        this.activity = activity;
    }

    @Scheduled(fixedDelay = 500)
    public void settle() {
        for (Object[] row : payments.pending()) {
            try {
                payments.settle((String) row[0], (String) row[1]);
            } catch (RuntimeException exception) {
                log.error("Payment worker failed for reference {}", row[0], exception);
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void remind() {
        for (Object[] row : activity.dueBills()) {
            try {
                activity.remind((String) row[0], (String) row[1]);
            } catch (RuntimeException exception) {
                log.error("Reminder worker failed for reference {}", row[0], exception);
            }
        }
    }

    @Scheduled(fixedDelay = 900_000)
    public void cleanup() {
        accounts.purgeExpired();
    }
}
