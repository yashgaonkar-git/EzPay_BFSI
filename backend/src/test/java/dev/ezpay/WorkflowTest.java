package dev.ezpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class WorkflowTest {
    private static final String PASSWORD = "Synthetic-Test-Password";
    private static final AtomicInteger TEST_CLIENTS = new AtomicInteger();
    private final String clientAddress = "192.0.2." + TEST_CLIENTS.incrementAndGet();
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[64];
        new SecureRandom().nextBytes(key);
        registry.add("ezpay.key", () -> Base64.getEncoder().encodeToString(key));
    }

    @Test
    void verifiesIdentityAndInvalidatesSessionsWhenPasswordChanges() throws Exception {
        String email = "profile@example.test";
        Cookie session = registerAndLogin(email, "9000000001");
        http.perform(get("/api/profile")).andExpect(status().isUnauthorized());
        http.perform(put("/api/profile").cookie(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        http.perform(put("/api/profile").cookie(session).header("X-EzPay-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Renamed User", "address", "Test address",
                    "photo", "", "confirmations", false, "reminders", false))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed User"));

        JsonNode reset = body(postJson("/api/auth/recover", null, Map.of("identity", "9000000001"))
            .andExpect(status().isOk()).andReturn());
        Map<String, Object> update = Map.of("challenge", reset.path("challenge").asText(), "code", reset.path("code").asText(),
            "password", "New-Synthetic-Password", "confirmation", "New-Synthetic-Password");
        postJson("/api/auth/reset", null, update).andExpect(status().isNoContent());
        http.perform(get("/api/profile").cookie(session)).andExpect(status().isUnauthorized());
        postJson("/api/auth/reset", null, update).andExpect(status().isBadRequest());
        postJson("/api/auth/login", null, Map.of("identity", email, "password", PASSWORD)).andExpect(status().isUnauthorized());
        Cookie renewed = login(email, "New-Synthetic-Password");
        http.perform(get("/api/profile").cookie(renewed)).andExpect(jsonPath("$.name").value("Renamed User"));
    }

    @Test
    void settlesIdempotentPaymentsAndRefundsDeclinesAndProtectsOwnership() throws Exception {
        Cookie payer = registerAndLogin("payer@example.test", "9000000002");
        Cookie another = registerAndLogin("another@example.test", "9000000003");
        link(payer);
        Map<String, Object> first = Map.of("key", "first", "method", "UPI", "amount", 2500,
            "recipient", "receiver@demo", "note", "First payment");
        String payment = submit(first, payer);
        assertEquals(payment, submit(first, payer));
        postJson("/api/payments", payer, Map.of("key", "first", "method", "UPI", "amount", 2600, "recipient", "receiver@demo"))
            .andExpect(status().isConflict());
        awaitState(payment, payer, "COMPLETED");
        http.perform(get("/api/payments/" + payment).cookie(another)).andExpect(status().isNotFound());
        http.perform(get("/api/payments").cookie(payer).param("q", "First payment").param("state", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        String declined = submit(Map.of("key", "declined", "method", "BANK", "amount", 1000,
            "account", "000000000", "ifsc", "DEMO0000001", "note", "Test decline"), payer);
        awaitState(declined, payer, "FAILED");
        http.perform(get("/api/profile").cookie(payer)).andExpect(jsonPath("$.balance").value(4_997_500));
        http.perform(get("/api/notifications").cookie(payer)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createsBillReminderAndRequiresStepUpForLargePayment() throws Exception {
        Cookie payer = registerAndLogin("bills@example.test", "9000000004");
        link(payer);
        LocalDate due = LocalDate.now(Payments.INDIA).plusDays(2);
        JsonNode bill = body(postJson("/api/bills", payer, Map.of("name", "Internet", "amount", 2_000_000,
            "dueDate", due.toString(), "monthly", true, "upi", "internet@demo"))
            .andExpect(status().isOk()).andReturn());
        http.perform(get("/api/notifications").cookie(payer)).andExpect(jsonPath("$[0].title").value("Bill due soon"));

        Map<String, Object> request = new HashMap<>(Map.of("key", "bill", "method", "UPI",
            "amount", 2_000_000, "recipient", "internet@demo", "billId", bill.path("id").asText()));
        JsonNode proof = body(postJson("/api/payments", payer, request).andExpect(status().isOk()).andReturn()).path("verification");
        assertTrue(proof.path("code").asText().matches("[0-9]{6}"));
        http.perform(get("/api/profile").cookie(payer)).andExpect(jsonPath("$.balance").value(5_000_000));
        request.put("challenge", proof.path("challenge").asText());
        request.put("code", proof.path("code").asText());
        awaitState(submit(request, payer), payer, "COMPLETED");
        http.perform(get("/api/bills").cookie(payer)).andExpect(jsonPath("$[0].dueDate").value(due.plusMonths(1).toString()));
        http.perform(get("/api/profile").cookie(payer)).andExpect(jsonPath("$.balance").value(3_000_000));
    }

    private Cookie registerAndLogin(String email, String mobile) throws Exception {
        JsonNode challenge = body(postJson("/api/auth/register", null,
            Map.of("name", "Test User", "email", email, "mobile", mobile, "password", PASSWORD))
            .andExpect(status().isOk()).andReturn());
        String code = challenge.path("code").asText();
        postJson("/api/auth/register/verify", null,
            Map.of("challenge", challenge.path("challenge").asText(), "code", code.equals("000000") ? "000001" : "000000"))
            .andExpect(status().isBadRequest());
        postJson("/api/auth/register/verify", null, challenge).andExpect(status().isNoContent());
        return login(email, PASSWORD);
    }

    private Cookie login(String email, String password) throws Exception {
        JsonNode challenge = body(postJson("/api/auth/login", null, Map.of("identity", email, "password", password))
            .andExpect(status().isOk()).andReturn());
        MvcResult result = postJson("/api/auth/login/verify", null, challenge).andExpect(status().isNoContent()).andReturn();
        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        return new Cookie("ezpay_session", cookie.split("[=;]", 3)[1]);
    }

    private void link(Cookie session) throws Exception {
        postJson("/api/profile/methods", session, Map.of("upi", "mywallet@demo",
            "account", "123456789012", "ifsc", "DEMO0000001")).andExpect(status().isOk());
    }

    private String submit(Map<String, Object> fields, Cookie session) throws Exception {
        return body(postJson("/api/payments", session, fields).andExpect(status().isOk()).andReturn())
            .path("payment").path("id").asText();
    }

    private void awaitState(String id, Cookie session, String expected) throws Exception {
        String state = "";
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            state = body(http.perform(get("/api/payments/" + id).cookie(session))
                .andExpect(status().isOk()).andReturn()).path("state").asText();
            if (expected.equals(state)) return;
            Thread.sleep(200);
        }
        assertEquals(expected, state, "Payment did not settle");
    }

    private ResultActions postJson(String path, Cookie session, Object fields) throws Exception {
        MockHttpServletRequestBuilder request = post(path).header("X-EzPay-Request", "1")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(fields))
            .with(mock -> {
                mock.setRemoteAddr(clientAddress);
                return mock;
            });
        if (session != null) request.cookie(session);
        return http.perform(request);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
