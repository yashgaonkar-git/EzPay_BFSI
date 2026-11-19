package dev.ezpay;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static dev.ezpay.ApiError.*;

@Service
class Payments {
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final long STEP_UP_AMOUNT = 2_000_000;
    private static final long TRANSFER_LIMIT = 10_000_000;
    private final Ledger store;
    private final Vault vault;
    private final Accounts accounts;

    record Request(String key, String method, Long amount, String recipient, String account,
                   String ifsc, String note, String billId, String challenge, String code) {}
    record Submission(Receipt payment, Accounts.DemoChallenge verification) {}
    record Receipt(String id, String method, long amount, String recipient, String note,
                   String state, String failure, String billId, Instant createdAt, Instant updatedAt) {}

    Payments(Ledger store, Vault vault, Accounts accounts) {
        this.store = store;
        this.vault = vault;
        this.accounts = accounts;
    }

    @Transactional(noRollbackFor = ApiError.class)
    public Submission submit(String memberId, Request input) {
        String key = required(input.key(), 64, "Request reference");
        String method = required(input.method(), 8, "Payment method");
        if (!method.equals("UPI") && !method.equals("BANK")) throw invalid("Choose UPI or bank transfer.");
        long amount = amount(input.amount());
        String recipient = method.equals("UPI") ? upi(input.recipient()) : "";
        String bank = method.equals("BANK") ? account(input.account()) : "";
        String routing = method.equals("BANK") ? ifsc(input.ifsc()) : "";
        String note = method.equals("BANK") ? required(input.note(), 160, "Purpose") : optional(input.note(), 160, "Note");
        String billId = optional(input.billId(), 36, "Bill reference");
        String fingerprint = vault.index(String.join("\n", method, Long.toString(amount),
            recipient, bank, routing, billId, note));
        Member member = store.lockMember(memberId);
        Payment existing = store.db().createQuery("from Payment p where p.memberId = :member and p.requestKey = :key", Payment.class)
            .setParameter("member", memberId).setParameter("key", key).getResultList().stream().findFirst().orElse(null);
        if (existing != null) {
            if (!existing.requestProof.equals(fingerprint)) throw new ApiError(409, "This payment reference is already in use.");
            return new Submission(receipt(existing), null);
        }

        Map<String, String> profile = accounts.privateProfile(member);
        if (method.equals("UPI") && !profile.containsKey("upi")) throw invalid("Link your demo UPI ID in Settings first.");
        if (method.equals("BANK") && !profile.containsKey("account")) throw invalid("Link your demo bank account in Settings first.");
        Bill bill = billId.isEmpty() ? null : getBill(memberId, billId);
        if (bill != null && (bill.paid || bill.activePayment != null || bill.amount != amount)) {
            throw new ApiError(409, "This bill is already paid, in progress, or has a different amount.");
        }
        if (bill != null && (!method.equals("UPI") || !recipient.equals(vault.open(bill.id, bill.privateDetails).get("upi")))) {
            throw invalid("Use the bill's saved UPI recipient.");
        }

        long recent = store.db().createQuery("select count(p) from Payment p where p.memberId = :id and p.createdAt >= :since and (p.failure is null or p.failure <> :blocked)", Long.class)
            .setParameter("id", memberId).setParameter("since", Instant.now().minus(1, ChronoUnit.MINUTES))
            .setParameter("blocked", "Blocked by payment risk checks.").getSingleResult();
        boolean blocked = amount > TRANSFER_LIMIT || recent >= 5;
        if (blocked) {
            Long alerts = store.db().createQuery("select count(p) from Payment p where p.memberId = :id and p.failure = :blocked and p.createdAt >= :since", Long.class)
                .setParameter("id", memberId).setParameter("since", Instant.now().minus(1, ChronoUnit.MINUTES))
                .setParameter("blocked", "Blocked by payment risk checks.").getSingleResult();
            if (alerts >= 3) throw new ApiError(429, "Payment attempts are temporarily limited. Try again in a minute.");
        }
        if (!blocked && member.balance >= amount && amount >= STEP_UP_AMOUNT) {
            if (input.challenge() == null || input.challenge().isBlank()) {
                return new Submission(null, accounts.issue("PAYMENT", memberId, Map.of("request", fingerprint, "key", key)));
            }
            Challenge challenge = accounts.verify("PAYMENT", new Accounts.Proof(input.challenge(), input.code()));
            if (!memberId.equals(challenge.subjectId) || !Map.of("request", fingerprint, "key", key).equals(vault.open(challenge.id, challenge.payload))) {
                throw invalid("Verification does not match this payment. Review the payment again.");
            }
        }

        Payment payment = new Payment();
        payment.id = Ledger.id();
        payment.memberId = memberId;
        payment.requestKey = key;
        payment.requestProof = fingerprint;
        payment.method = method;
        payment.amount = amount;
        payment.privateDetails = vault.seal(payment.id, Map.of("upi", recipient, "account", bank, "ifsc", routing, "note", note));
        payment.billId = billId.isEmpty() ? null : billId;
        payment.createdAt = payment.updatedAt = Instant.now();
        if (blocked) {
            payment.state = "FAILED";
            payment.failure = "Blocked by payment risk checks.";
            store.notice(memberId, "Payment blocked", "A recent payment attempt was blocked by risk checks.");
        } else if (member.balance < amount) {
            payment.state = "FAILED";
            payment.failure = "Insufficient demo balance.";
        } else {
            payment.state = "INITIATED";
            member.balance -= amount;
            if (bill != null) bill.activePayment = payment.id;
        }
        store.db().persist(payment);
        store.audit(memberId, "PAYMENT_" + payment.state, payment.id);
        return new Submission(receipt(payment), null);
    }

    @Transactional(readOnly = true)
    public Receipt get(String memberId, String id) {
        Payment payment = store.db().find(Payment.class, id);
        if (payment == null || !memberId.equals(payment.memberId)) throw missing();
        return receipt(payment);
    }

    @Transactional(readOnly = true)
    public List<Receipt> history(String id, String method, String state, LocalDate from, LocalDate to, String search) {
        StringBuilder query = new StringBuilder("from Payment p where p.memberId = :member");
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("member", id);
        if (from != null && to != null && from.isAfter(to)) throw invalid("The start date must come before the end date.");
        if (to != null && to.getYear() > 9999) throw invalid("Enter a valid end date.");
        if (method != null && !method.isBlank()) {
            query.append(" and p.method = :method");
            parameters.put("method", method);
        }
        if (state != null && !state.isBlank()) {
            query.append(" and p.state = :state");
            parameters.put("state", state);
        }
        if (from != null) {
            query.append(" and p.createdAt >= :fromDate");
            parameters.put("fromDate", from.atStartOfDay(INDIA).toInstant());
        }
        if (to != null) {
            query.append(" and p.createdAt < :toDate");
            parameters.put("toDate", to.plusDays(1).atStartOfDay(INDIA).toInstant());
        }
        var statement = store.db().createQuery(query + " order by p.createdAt desc, p.id desc", Payment.class);
        parameters.forEach(statement::setParameter);
        String term = optional(search, 160, "Search").toLowerCase(Locale.ROOT);
        List<Receipt> results = new ArrayList<>();
        try (var rows = statement.getResultStream()) {
            rows.map(this::receipt).filter(item -> term.isEmpty() || searchable(item).contains(term)).forEach(results::add);
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<Object[]> pending() {
        return store.db().createQuery("select p.id, p.memberId from Payment p where p.state in ('INITIATED', 'PROCESSING') order by p.createdAt", Object[].class)
            .setMaxResults(100).getResultList();
    }

    @Transactional
    public void settle(String id, String memberId) {
        Member member = store.lockMember(memberId);
        Payment payment = store.db().find(Payment.class, id);
        if (payment == null) return;
        Instant now = Instant.now();
        if (payment.state.equals("INITIATED")) {
            if (payment.createdAt.plusMillis(500).isAfter(now)) return;
            payment.state = "PROCESSING";
        } else if (payment.state.equals("PROCESSING")) {
            if (payment.updatedAt.plusMillis(750).isAfter(now)) return;
            Map<String, String> data = vault.open(payment.id, payment.privateDetails);
            boolean failed = data.get("upi").endsWith("@declined") || data.get("account").equals("000000000");
            payment.state = failed ? "FAILED" : "COMPLETED";
            if (failed) {
                payment.failure = "The demo payment provider declined the transfer.";
                member.balance += payment.amount;
            }
            if (payment.billId != null) {
                Bill bill = getBill(memberId, payment.billId);
                if (Objects.equals(bill.activePayment, id)) {
                    bill.activePayment = null;
                    if (!failed && bill.monthly) {
                        if (bill.dueDay == null) bill.dueDay = bill.dueDate.getDayOfMonth();
                        YearMonth next = YearMonth.from(bill.dueDate).plusMonths(1);
                        bill.dueDate = next.atDay(Math.min(bill.dueDay, next.lengthOfMonth()));
                    } else if (!failed) {
                        bill.paid = true;
                    }
                }
            }
            if (failed || member.confirmations) {
                store.notice(memberId, failed ? "Payment failed" : "Payment complete",
                    (failed ? payment.failure : "Your " + payment.method + " payment was completed.") + " Reference " + payment.id + ".");
            }
        } else {
            return;
        }
        payment.updatedAt = now;
        store.audit(memberId, "PAYMENT_" + payment.state, id);
    }

    Bill getBill(String owner, String id) {
        Bill bill = store.db().find(Bill.class, id);
        if (bill == null || !owner.equals(bill.memberId)) throw missing();
        return bill;
    }

    private Receipt receipt(Payment payment) {
        Map<String, String> data = vault.open(payment.id, payment.privateDetails);
        String bank = data.get("account");
        String recipient = payment.method.equals("UPI") ? data.get("upi") :
            "•••• " + bank.substring(bank.length() - 4) + " · " + data.get("ifsc");
        return new Receipt(payment.id, payment.method, payment.amount, recipient, data.get("note"),
            payment.state, payment.failure, payment.billId, payment.createdAt, payment.updatedAt);
    }

    private static String searchable(Receipt payment) {
        return String.join(" ", payment.id(), payment.recipient(), payment.note(), payment.method(), payment.state()).toLowerCase(Locale.ROOT);
    }

    static long amount(Long paise) {
        if (paise == null || paise < 1 || paise > 99_999_999_999L) throw invalid("Enter a valid positive amount.");
        return paise;
    }

    static String upi(String raw) {
        String result = required(raw, 100, "UPI ID").toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9][a-z0-9._-]{1,79}@[a-z][a-z0-9]{1,19}")) throw invalid("Enter a valid UPI ID, such as name@demo.");
        return result;
    }

    static String account(String raw) {
        String result = required(raw, 18, "Account number");
        if (!result.matches("[0-9]{9,18}")) throw invalid("Enter a bank account number with 9 to 18 digits.");
        return result;
    }

    static String ifsc(String raw) {
        String result = required(raw, 11, "IFSC").toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw invalid("Enter a valid 11-character IFSC, such as DEMO0000001.");
        return result;
    }
}
