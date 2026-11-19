package dev.ezpay;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static dev.ezpay.ApiError.*;

@Service
class Accounts {
    record Registration(String name, String email, String mobile, String password) {}
    record Credentials(String identity, String password) {}
    record Proof(String challenge, String code) {}
    record Reset(String challenge, String code, String password, String confirmation) {}
    record ProfileChange(String name, String address, String photo, boolean confirmations, boolean reminders) {}
    record Link(String upi, String account, String ifsc) {}
    record Profile(String name, String email, String mobile, String address, String photo, String upi,
                   String bank, long balance, boolean confirmations, boolean reminders) {}
    record DemoChallenge(String challenge, String code) {}

    private final Ledger store;
    private final Vault vault;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final String dummyHash = bcrypt.encode("dummy credential");
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private record AttemptWindow(long minute, int count) {}

    Accounts(Ledger store, Vault vault) {
        this.store = store;
        this.vault = vault;
    }

    void throttle(String address) {
        long minute = Instant.now().getEpochSecond() / 60;
        if (attempts.size() > 200) attempts.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
        AttemptWindow current = attempts.compute(address, (key, previous) ->
            new AttemptWindow(minute, previous != null && previous.minute() == minute ? previous.count() + 1 : 1));
        if (current.count() > 30) throw new ApiError(429, "Too many attempts. Try again in a minute.");
    }

    @Transactional
    public DemoChallenge register(Registration input) {
        String name = required(input.name(), 80, "Name");
        String email = required(input.email(), 150, "Email").toLowerCase(Locale.ROOT);
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw invalid("Enter a valid email address.");
        String mobile = mobile(input.mobile());
        validatePassword(input.password());
        if (find(email) != null || find(mobile) != null) throw duplicate();
        return issue("REGISTER", null, Map.of("name", name, "email", email, "mobile", mobile,
            "passwordHash", bcrypt.encode(input.password())));
    }

    @Transactional(noRollbackFor = ApiError.class)
    public void completeRegistration(Proof input) {
        Challenge challenge = verify("REGISTER", input);
        Map<String, String> profile = vault.open(challenge.id, challenge.payload);
        if (find(profile.get("email")) != null || find(profile.get("mobile")) != null) throw duplicate();
        Member member = new Member();
        member.id = Ledger.id();
        member.emailKey = vault.index(profile.get("email"));
        member.mobileKey = vault.index(profile.get("mobile"));
        member.passwordHash = profile.remove("passwordHash");
        member.privateProfile = vault.seal(member.id, profile);
        member.balance = 5_000_000;
        member.createdAt = Instant.now();
        store.db().persist(member);
        store.audit(member.id, "REGISTERED", null);
    }

    @Transactional
    public DemoChallenge login(Credentials input) {
        String identity = identity(input.identity());
        Member member = find(identity);
        String password = input.password();
        if (password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
            || !bcrypt.matches(password, member == null ? dummyHash : member.passwordHash) || member == null) {
            throw new ApiError(401, "The email, mobile number or password is incorrect.");
        }
        return issue("LOGIN", member.id, Map.of("credential", member.passwordHash));
    }

    @Transactional(noRollbackFor = ApiError.class)
    public String completeLogin(Proof input) {
        Challenge challenge = verify("LOGIN", input);
        Member member = store.lockMember(challenge.subjectId);
        if (!member.passwordHash.equals(vault.open(challenge.id, challenge.payload).get("credential"))) {
            throw invalid("Your password changed. Start sign-in again.");
        }
        String raw = vault.token();
        LoginSession session = new LoginSession();
        session.id = vault.index(raw);
        session.memberId = challenge.subjectId;
        session.expiresAt = Instant.now().plus(8, ChronoUnit.HOURS);
        store.db().persist(session);
        store.audit(session.memberId, "SIGNED_IN", null);
        return raw;
    }

    @Transactional
    public DemoChallenge recover(String rawIdentity) {
        Member member = find(identity(rawIdentity));
        return issue("RESET", member == null ? null : member.id,
            member == null ? Map.of() : Map.of("credential", member.passwordHash));
    }

    @Transactional(noRollbackFor = ApiError.class)
    public void reset(Reset input) {
        validatePassword(input.password());
        if (!input.password().equals(input.confirmation())) throw invalid("The passwords do not match.");
        Challenge challenge = verify("RESET", new Proof(input.challenge(), input.code()));
        if (challenge.subjectId == null) throw invalid("This reset link is invalid or expired.");
        Member member = store.lockMember(challenge.subjectId);
        if (!member.passwordHash.equals(vault.open(challenge.id, challenge.payload).get("credential"))) {
            throw invalid("This reset link is invalid or expired.");
        }
        member.passwordHash = bcrypt.encode(input.password());
        store.db().createQuery("delete from LoginSession s where s.memberId = :member")
            .setParameter("member", member.id).executeUpdate();
        store.audit(member.id, "PASSWORD_RESET", null);
    }

    @Transactional(readOnly = true)
    public String authenticated(String token) {
        if (token == null || token.length() != 43) throw unauthorized();
        LoginSession session = store.db().find(LoginSession.class, vault.index(token));
        if (session == null || !session.expiresAt.isAfter(Instant.now())) throw unauthorized();
        return session.memberId;
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.length() != 43) return;
        LoginSession session = store.db().find(LoginSession.class, vault.index(token));
        if (session != null) {
            store.db().remove(session);
            store.audit(session.memberId, "SIGNED_OUT", null);
        }
    }

    @Transactional(readOnly = true)
    public Profile get(String id) {
        return profile(store.db().find(Member.class, id));
    }

    @Transactional
    public Profile update(String id, ProfileChange input) {
        String name = required(input.name(), 80, "Name");
        String address = optional(input.address(), 240, "Address");
        String photo = optional(input.photo(), 450_000, "Profile picture");
        if (!photo.isEmpty() && !photo.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+")) {
            throw invalid("Choose a PNG, JPEG or WebP profile picture.");
        }
        Member member = store.lockMember(id);
        Map<String, String> data = privateProfile(member);
        data.put("name", name);
        data.put("address", address);
        data.put("photo", photo);
        member.privateProfile = vault.seal(id, data);
        member.confirmations = input.confirmations();
        member.reminders = input.reminders();
        store.audit(id, "PROFILE_UPDATED", null);
        return profile(member);
    }

    @Transactional
    public Profile link(String id, Link input) {
        String upi = optional(input.upi(), 100, "UPI ID");
        String account = optional(input.account(), 18, "Bank account");
        String ifsc = optional(input.ifsc(), 11, "IFSC");
        if (upi.isEmpty() && account.isEmpty() && ifsc.isEmpty()) throw invalid("Enter a UPI ID or bank account.");
        Member member = store.lockMember(id);
        Map<String, String> data = privateProfile(member);
        if (!upi.isEmpty()) data.put("upi", Payments.upi(upi));
        if (!account.isEmpty() || !ifsc.isEmpty()) {
            data.put("account", Payments.account(account));
            data.put("ifsc", Payments.ifsc(ifsc));
        }
        member.privateProfile = vault.seal(id, data);
        store.audit(id, "DEMO_PAYMENT_METHOD_LINKED", null);
        return profile(member);
    }

    Map<String, String> privateProfile(Member member) {
        return new HashMap<>(vault.open(member.id, member.privateProfile));
    }

    private Profile profile(Member member) {
        Map<String, String> data = privateProfile(member);
        String bank = data.getOrDefault("account", "");
        if (!bank.isEmpty()) bank = "•••• " + bank.substring(bank.length() - 4) + " · " + data.get("ifsc");
        return new Profile(data.get("name"), data.get("email"), data.get("mobile"),
            data.getOrDefault("address", ""), data.getOrDefault("photo", ""), data.getOrDefault("upi", ""),
            bank, member.balance, member.confirmations, member.reminders);
    }

    DemoChallenge issue(String purpose, String subjectId, Map<String, String> payload) {
        String code = purpose.equals("RESET") ? vault.token() : vault.otp();
        Challenge challenge = new Challenge();
        challenge.id = vault.token();
        challenge.purpose = purpose;
        challenge.subjectId = subjectId;
        challenge.proof = vault.index(code);
        challenge.payload = vault.seal(challenge.id, payload);
        challenge.expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        store.db().persist(challenge);
        return new DemoChallenge(challenge.id, code);
    }

    Challenge verify(String purpose, Proof input) {
        String challengeId = required(input.challenge(), 80, "Verification request");
        String code = required(input.code(), 80, "Verification code");
        Challenge challenge = store.db().find(Challenge.class, challengeId, LockModeType.PESSIMISTIC_WRITE);
        if (challenge == null || !purpose.equals(challenge.purpose) || !challenge.expiresAt.isAfter(Instant.now())
            || challenge.attempts >= 5) {
            throw invalid("This verification has expired. Request a new one.");
        }
        if (!vault.matches(challenge.proof, code)) {
            challenge.attempts++;
            throw invalid("Incorrect verification code. Check the code or request a new one.");
        }
        store.db().remove(challenge);
        return challenge;
    }

    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        store.db().createQuery("delete from Challenge c where c.expiresAt < :now").setParameter("now", now).executeUpdate();
        store.db().createQuery("delete from LoginSession s where s.expiresAt < :now").setParameter("now", now).executeUpdate();
    }

    private Member find(String identity) {
        String property = identity.contains("@") ? "emailKey" : "mobileKey";
        return store.db().createQuery("from Member m where m." + property + " = :key", Member.class)
            .setParameter("key", vault.index(identity)).getResultStream().findFirst().orElse(null);
    }

    private static String identity(String value) {
        String raw = required(value, 150, "Email or mobile");
        return raw.contains("@") ? raw.toLowerCase(Locale.ROOT) : mobile(raw);
    }

    private static String mobile(String value) {
        String phone = required(value, 24, "Mobile").replaceAll("[ ()-]", "");
        if (phone.matches("\\d{10}")) phone = "+91" + phone;
        if (!phone.matches("\\+[1-9]\\d{7,14}")) throw invalid("Enter a 10-digit Indian mobile or an international number with country code.");
        return phone;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw invalid("Use a password with at least 12 characters and at most 72 UTF-8 bytes.");
        }
    }

    private static ApiError duplicate() {
        return new ApiError(409, "An account with that email or mobile number already exists.");
    }

    private static ApiError unauthorized() {
        return new ApiError(401, "Sign in to continue.");
    }
}
