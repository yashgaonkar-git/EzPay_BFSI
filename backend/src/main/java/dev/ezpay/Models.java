package dev.ezpay;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ez_users")
class Member {
    @Id String id;
    @Column(unique = true, nullable = false) String emailKey;
    @Column(unique = true, nullable = false) String mobileKey;
    String passwordHash;
    @Lob String privateProfile;
    long balance;
    boolean confirmations = true;
    boolean reminders = true;
    Instant createdAt;
}

@Entity
@Table(name = "ez_challenges")
class Challenge {
    @Id String id;
    String purpose;
    String subjectId;
    String proof;
    @Lob String payload;
    Instant expiresAt;
    int attempts;
}

@Entity
@Table(name = "ez_sessions", indexes = @Index(columnList = "memberId"))
class LoginSession {
    @Id String id;
    String memberId;
    Instant expiresAt;
}

@Entity
@Table(name = "ez_payments", uniqueConstraints = @UniqueConstraint(columnNames = {"memberId", "requestKey"}),
       indexes = @Index(columnList = "state,createdAt"))
class Payment {
    @Id String id;
    String memberId;
    String requestKey;
    String requestProof;
    String method;
    long amount;
    @Lob String privateDetails;
    String state;
    String failure;
    String billId;
    Instant createdAt;
    Instant updatedAt;
}

@Entity
@Table(name = "ez_bills", indexes = @Index(columnList = "dueDate"))
class Bill {
    @Id String id;
    String memberId;
    @Lob String privateDetails;
    long amount;
    LocalDate dueDate;
    boolean monthly;
    Integer dueDay;
    boolean paid;
    LocalDate remindedFor;
    String activePayment;
}

@Entity
@Table(name = "ez_notices", indexes = @Index(columnList = "memberId,createdAt"))
class Notice {
    @Id String id;
    String memberId;
    String title;
    @Lob String privateMessage;
    Instant createdAt;
    boolean seen;
}

@Entity
@Table(name = "ez_audit")
class AuditEvent {
    @Id String id;
    String memberId;
    String eventType;
    String referenceId;
    Instant createdAt;
}
