package dev.ezpay;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
class Ledger {
    private final EntityManager db;
    private final Vault vault;

    Ledger(EntityManager db, Vault vault) {
        this.db = db;
        this.vault = vault;
    }

    public EntityManager db() {
        return db;
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    Member lockMember(String id) {
        Member member = db.find(Member.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (member == null) throw ApiError.missing();
        return member;
    }

    void audit(String memberId, String type, String reference) {
        AuditEvent event = new AuditEvent();
        event.id = id();
        event.memberId = memberId;
        event.eventType = type;
        event.referenceId = reference;
        event.createdAt = Instant.now();
        db.persist(event);
    }

    void notice(String memberId, String title, String message) {
        Notice notice = new Notice();
        notice.id = id();
        notice.memberId = memberId;
        notice.title = title;
        notice.privateMessage = vault.seal(notice.id, java.util.Map.of("message", message));
        notice.createdAt = Instant.now();
        db.persist(notice);
    }
}
