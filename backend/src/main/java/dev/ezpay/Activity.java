package dev.ezpay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static dev.ezpay.ApiError.*;

@Service
class Activity {
    record BillInput(String name, Long amount, LocalDate dueDate, boolean monthly, String upi) {}
    record BillView(String id, String name, long amount, LocalDate dueDate, boolean monthly, String upi, String state) {}
    record Notification(String id, String title, String message, Instant createdAt, boolean seen) {}

    private final Ledger store;
    private final Vault vault;
    private final Payments payments;

    Activity(Ledger store, Vault vault, Payments payments) {
        this.store = store;
        this.vault = vault;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public List<BillView> bills(String memberId) {
        return store.db().createQuery("from Bill b where b.memberId = :id order by b.dueDate, b.id", Bill.class)
            .setParameter("id", memberId).getResultList().stream().map(this::billView).toList();
    }

    @Transactional
    public BillView addBill(String memberId, BillInput input) {
        String name = required(input.name(), 100, "Bill name");
        long amount = Payments.amount(input.amount());
        LocalDate today = LocalDate.now(Payments.INDIA);
        if (input.dueDate() == null || input.dueDate().isBefore(today.minusYears(10)) || input.dueDate().isAfter(today.plusYears(10))) {
            throw invalid("Enter a due date within ten years of today.");
        }
        String upi = Payments.upi(input.upi());
        store.lockMember(memberId);
        Bill bill = new Bill();
        bill.id = Ledger.id();
        bill.memberId = memberId;
        bill.privateDetails = vault.seal(bill.id, Map.of("name", name, "upi", upi));
        bill.amount = amount;
        bill.dueDate = input.dueDate();
        bill.monthly = input.monthly();
        bill.dueDay = input.monthly() ? bill.dueDate.getDayOfMonth() : null;
        store.db().persist(bill);
        store.audit(memberId, "BILL_ADDED", bill.id);
        remind(bill, store.db().find(Member.class, memberId), today);
        return billView(bill);
    }

    @Transactional(readOnly = true)
    public List<Object[]> dueBills() {
        LocalDate today = LocalDate.now(Payments.INDIA);
        return store.db().createQuery("""
            select b.id, b.memberId from Bill b, Member m
            where m.id = b.memberId and m.reminders = true and b.paid = false and b.activePayment is null
            and b.dueDate between :today and :soon and (b.remindedFor is null or b.remindedFor <> b.dueDate)
            order by b.dueDate
            """, Object[].class).setParameter("today", today).setParameter("soon", today.plusDays(3))
            .setMaxResults(100).getResultList();
    }

    @Transactional
    public void remind(String billId, String memberId) {
        Member member = store.lockMember(memberId);
        remind(payments.getBill(memberId, billId), member, LocalDate.now(Payments.INDIA));
    }

    private void remind(Bill bill, Member member, LocalDate today) {
        if (!member.reminders || bill.paid || bill.activePayment != null || bill.dueDate.equals(bill.remindedFor)
            || bill.dueDate.isBefore(today) || bill.dueDate.isAfter(today.plusDays(3))) return;
        store.notice(member.id, "Bill due soon", billView(bill).name() + " is due on " + bill.dueDate + ".");
        bill.remindedFor = bill.dueDate;
        store.audit(member.id, "REMINDER_CREATED", bill.id);
    }

    @Transactional(readOnly = true)
    public List<Notification> notices(String memberId) {
        return store.db().createQuery("from Notice n where n.memberId = :id order by n.createdAt desc", Notice.class)
            .setParameter("id", memberId).setMaxResults(100).getResultList().stream()
            .map(n -> new Notification(n.id, n.title, vault.open(n.id, n.privateMessage).get("message"), n.createdAt, n.seen))
            .toList();
    }

    @Transactional
    public void markRead(String memberId, String id) {
        store.lockMember(memberId);
        Notice notice = store.db().find(Notice.class, id);
        if (notice == null || !memberId.equals(notice.memberId)) throw missing();
        notice.seen = true;
    }

    private BillView billView(Bill bill) {
        Map<String, String> details = vault.open(bill.id, bill.privateDetails);
        String state = bill.paid ? "PAID" : bill.activePayment != null ? "PROCESSING" :
            bill.dueDate.isBefore(LocalDate.now(Payments.INDIA)) ? "OVERDUE" : "UPCOMING";
        return new BillView(bill.id, details.get("name"), bill.amount, bill.dueDate, bill.monthly, details.get("upi"), state);
    }
}
