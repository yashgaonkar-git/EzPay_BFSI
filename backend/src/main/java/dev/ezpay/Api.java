package dev.ezpay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api")
class Api {
    private static final String SESSION = "ezpay_session";
    private final Accounts accounts;
    private final Payments payments;
    private final Activity activity;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    Api(Accounts accounts, Payments payments, Activity activity, HttpServletRequest request, HttpServletResponse response) {
        this.accounts = accounts;
        this.payments = payments;
        this.activity = activity;
        this.request = request;
        this.response = response;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "up", "mode", "sandbox");
    }

    @PostMapping("/auth/register")
    Accounts.DemoChallenge register(@RequestBody Accounts.Registration input) {
        authRequest();
        return accounts.register(input);
    }

    @PostMapping("/auth/register/verify")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void registerVerify(@RequestBody Accounts.Proof input) {
        authRequest();
        accounts.completeRegistration(input);
    }

    @PostMapping("/auth/login")
    Accounts.DemoChallenge login(@RequestBody Accounts.Credentials input) {
        authRequest();
        return accounts.login(input);
    }

    @PostMapping("/auth/login/verify")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void loginVerify(@RequestBody Accounts.Proof input) {
        authRequest();
        cookie(accounts.completeLogin(input), Duration.ofHours(8));
    }

    record Recovery(String identity) {}

    @PostMapping("/auth/recover")
    Accounts.DemoChallenge recover(@RequestBody Recovery input) {
        authRequest();
        return accounts.recover(input.identity());
    }

    @PostMapping("/auth/reset")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void reset(@RequestBody Accounts.Reset input) {
        authRequest();
        accounts.reset(input);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = SESSION, required = false) String token) {
        mutation();
        accounts.logout(token);
        cookie("", Duration.ZERO);
    }

    @GetMapping("/profile")
    Accounts.Profile profile(@CookieValue(name = SESSION, required = false) String token) {
        return accounts.get(accounts.authenticated(token));
    }

    @PutMapping("/profile")
    Accounts.Profile profile(@CookieValue(name = SESSION, required = false) String token, @RequestBody Accounts.ProfileChange input) {
        mutation();
        return accounts.update(accounts.authenticated(token), input);
    }

    @PostMapping("/profile/methods")
    Accounts.Profile link(@CookieValue(name = SESSION, required = false) String token, @RequestBody Accounts.Link input) {
        mutation();
        return accounts.link(accounts.authenticated(token), input);
    }

    @PostMapping("/payments")
    Payments.Submission submit(@CookieValue(name = SESSION, required = false) String token, @RequestBody Payments.Request input) {
        authRequest();
        return payments.submit(accounts.authenticated(token), input);
    }

    @GetMapping("/payments")
    List<Payments.Receipt> history(@CookieValue(name = SESSION, required = false) String token,
                                   @RequestParam(required = false) String method, @RequestParam(required = false) String state,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) String q) {
        return payments.history(accounts.authenticated(token), method, state, from, to, q);
    }

    @GetMapping("/payments/{id}")
    Payments.Receipt payment(@CookieValue(name = SESSION, required = false) String token, @PathVariable String id) {
        return payments.get(accounts.authenticated(token), id);
    }

    @GetMapping("/bills")
    List<Activity.BillView> bills(@CookieValue(name = SESSION, required = false) String token) {
        return activity.bills(accounts.authenticated(token));
    }

    @PostMapping("/bills")
    Activity.BillView addBill(@CookieValue(name = SESSION, required = false) String token, @RequestBody Activity.BillInput input) {
        mutation();
        return activity.addBill(accounts.authenticated(token), input);
    }

    @GetMapping("/notifications")
    List<Activity.Notification> notifications(@CookieValue(name = SESSION, required = false) String token) {
        return activity.notices(accounts.authenticated(token));
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void markRead(@CookieValue(name = SESSION, required = false) String token, @PathVariable String id) {
        mutation();
        activity.markRead(accounts.authenticated(token), id);
    }

    private void authRequest() {
        mutation();
        accounts.throttle(request.getRemoteAddr());
    }

    private void mutation() {
        if (!"1".equals(request.getHeader("X-EzPay-Request"))) {
            throw new ApiError(403, "The request could not be verified.");
        }
    }

    private void cookie(String token, Duration duration) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION, token)
            .path("/api").httpOnly(true).sameSite("Strict").secure(request.isSecure()).maxAge(duration).build().toString());
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, String>> expected(ApiError error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message", "One or more fields are invalid. Check the form and try again."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "This change conflicts with an existing record."));
    }
}
