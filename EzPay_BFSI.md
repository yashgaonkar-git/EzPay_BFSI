# EzPay business and implementation specification

Source document: EzPay_BFSI.pdf, 36 pages, HCLTech Career Shaper business requirements document. The pages describe product requirements and example user interactions. Slide viewer controls, branding, blank template cells, and the closing slide are document material.

## Product scope

EzPay is a digital payments workspace for user registration and account management, UPI and bank payments, transaction tracking, bills, and alerts. Users are assumed to have network access, accurate registration details, and suitable payment accounts. The original project also assumes access to participating banks and UPI APIs, compliance with applicable data protection and financial rules, and delivery within an agreed budget and timeframe. Dates and budget are unspecified.

The product owner manages vision and scope. Development builds and maintains the application. QA verifies quality. Customer support assists users. End users make payments and manage their transaction activity. The BRD introduces MoSCoW (Must, Should, Could, Won’t Have) without assigning those labels to individual user stories. Its security table assigns Highest priority and its support table assigns High priority.

## Page-by-page traceability

| PDF page | Content | EzPay coverage |
| --- | --- | --- |
| 1 | EzPay secure and efficient digital payments BRD cover | Product name and objective. |
| 2 | Overview and six use cases | Requirements organized under the six areas below. |
| 3 | Six use-case titles | User management, payments, history, notifications, security, and UI. |
| 4 | Project details template | Project type, dates, business analyst and Scrum Master fields are blank. |
| 5 | Digital payments overview | Dashboard, payment flows, history and alerts with responsive layouts. |
| 6 | Key features | Registration, profile, UPI, bank, status, reminders, encryption and fraud controls. |
| 7 | Stakeholders and stack | Java, Angular, Spring Boot, Hibernate and an Oracle configuration. |
| 8 | Assumptions and constraints | Provider, regulatory, scale, budget and schedule dependencies documented here. |
| 9 | Functional requirements | Six use-case groups below, including MFA, payment methods, search and date-based alerts. |
| 10 | Non-functional requirements | 99.9% uptime, intuitive UI, scalable capacity, encrypted transmission, PCI DSS target, three-second processing and 10,000 concurrent users. Production targets require separate validation. |
| 11 | Access and security | Strong authentication, encryption, fraud prevention, security reviews and applicable privacy controls. |
| 12 | Support and maintenance | 24/7 chat, email and phone, updates, audit logs, backup and disaster recovery. Persistent audit events implemented. Staffed support and recovery operations depend on the operator. |
| 13 | MoSCoW prioritization | Priority definitions retained above. |
| 14 | User story 1.1 registration/login | Valid email/mobile, database account creation and authenticated session. |
| 15 | Registration/login paths | Name, email, mobile, password, code, confirmation and subsequent login. Duplicate, invalid code, and retry paths. |
| 16 | Login wireframe labels | Credentials, verification, OTP, invalid attempt, retry and recovery states. |
| 17 | User story 1.2 profile | View and edit name, address, profile photo and notification preferences. |
| 18 | Wireframe labeled 1.2 | Signup, created and duplicate account states implemented under registration. |
| 19 | User story 1.3 password recovery | Start with registered email/mobile, open reset link, confirm strong new password, and invalidate sessions. A generic request response prevents account discovery. |
| 20 | User story 2.1 UPI | Link demo UPI, enter recipient, amount and optional note, review, submit and receive status. Invalid UPI and insufficient funds handled. |
| 21 | User story 2.2 bank transfer | Link demo bank, enter recipient account and IFSC, amount and purpose, review and submit. Account/IFSC validation and failure reasons included. |
| 22 | Wireframe labeled bank story 2.2 | Wireframe content describes bills. EzPay supports add bill, saved confirmation, upcoming/overdue views, and a bill payment screen alongside the separate bank flow. |
| 23 | User story 3.1 history | Search by recipient, reference or note. Filter by dates, payment type and status. Show amount, counterpart, date, details and empty state. |
| 24 | User story 3.2 track status | Scoped receipt view polling for initiated, processing, completed or failed with last update and failure reason. |
| 25 | User story 4.1 confirmations | Persistent inbox and opt-in desktop alerts while the page is open. Live push and email delivery are deployment dependencies. |
| 26 | User story 4.2 reminders | One reminder per occurrence for due dates within three days, subject to user preference. Recurring bills roll to the next month after payment. |
| 27 | User story 5.1 encryption | Encrypt stored customer details, transfer identifiers, bill details and messages. Use HTTPS for deployed transport. |
| 28 | User story 5.2 fraud prevention | Amount and velocity rules, blocked attempts, alerts, step-up verification and authentication attempt limits. Production fraud feeds and risk models depend on provider integration. |
| 29 | User story 6.1 responsive UI | Layouts and controls adapt to smartphone, tablet and desktop widths. |
| 30 | User story 6.2 navigation | Dashboard shortcuts, grouped menu, feature search, contextual guidance and browser back navigation. |
| 31 | Class diagram | User/profile, authentication, password reset, payment and history, reminders, encryption, navigation and fraud responsibilities represented by services, records and UI state. |
| 32 | ER diagram | Member-owned payment, bill, challenge, session, alert and audit records. UPI and bank share a transaction ledger with a method discriminator. |
| 33 | Account sequence diagram | Registration and session verification, profile update/read, reset proof validation and password change. |
| 34 | Payment and supporting sequence diagram | Session-gated UPI/bank requests, history, bill reminders, encryption, navigation and risk checks. |
| 35 | Layered architecture | Angular presentation and printable reports, Spring MVC REST/business services, Hibernate data access, configurable Oracle. Java 17 and Oracle 19c or newer are the implementation targets. Java 8 and Oracle 11g are legacy versions shown in the diagram. |
| 36 | Closing slide | Presentation closing material. |

## Run EzPay

Use Java 17 or newer, Maven 3.6.3 or newer, and Node.js 22.12+ on the 22.x line or Node.js 24.x. The project uses Spring Boot 3.5 and Angular 21. [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) · [Angular compatibility](https://angular.dev/reference/versions)

From the project directory:

```bash
cd frontend
npm ci
npm run build
cd ../backend
mvn clean package
java -jar target/ezpay-1.0.0.jar
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). **Open demo account** creates an isolated test account with a demo balance and both payment methods. To keep a test account across sign-ins, choose **Create an account** and use test contact details. The sandbox displays the OTP and recovery link in the relevant screens.

For frontend development, run `mvn spring-boot:run` from `backend` and `npm start` from `frontend` in separate shells. Open [http://127.0.0.1:4200](http://127.0.0.1:4200). The dev server proxies `/api` to Spring Boot.

By default the backend stores data in H2 under `backend/data` when started from `backend`. Retain the database and `vault.key` together for local restores. To supply key material separately, set `EZPAY_KEY` to a base64 encoding of 64 random bytes and keep that value with the database backups through a separate secret store. For example, generate fresh material with `openssl rand -base64 64` and remove its line breaks before assigning it. Changing the key requires data migration.

For an Oracle test instance, activate the `oracle` Spring profile and set `EZPAY_ORACLE_URL`, `EZPAY_ORACLE_USER`, `EZPAY_ORACLE_PASSWORD`, and `EZPAY_KEY`. The schema update setting is for sandbox use. Validate schema migrations against the agreed Oracle release before deployment. [Oracle JDBC interoperability](https://docs.oracle.com/en/database/oracle/oracle-database/26/jjdbc/JDBC-getting-started.html)

## Try the workflows

1. Open a demo account and send ₹100 to `recipient@demo` or to bank account `123456789012` with IFSC `DEMO0000001` and a transfer purpose.
2. Confirm the amount and recipient. Watch the receipt move through initiated, processing and completed. Transactions lists completed and failed attempts and supports **Print / save as PDF** through the browser.
3. Send to `recipient@declined` or account `000000000` with a valid demo IFSC to exercise a provider decline. The ledger releases the reserved amount. Transfers from ₹20,000 with sufficient balance use step-up verification. Very large or rapid repeats exercise the risk controls.
4. Add a bill due within three days, look for its reminder in Notifications, and pay it using **Pay bill**. A paid one-time bill closes. A completed monthly bill advances to the next occurrence.
5. Edit your profile and toggle confirmation/reminder preferences. The notifications inbox retains prior records. Use **Forgot password?** with the registered contact to test recovery and session invalidation.

## Implementation and verification

Angular serves the UI. Spring MVC handles JSON endpoints. Spring services apply identity, payment, reminder and risk rules. Hibernate handles persistence. Payment amounts use integer paise. A per-member database lock guards balance reservation and settlement. Idempotency keys bind repeat submissions to their original details. The scheduler advances persisted payment states and deduplicates reminders per due date. AES-GCM encrypts private fields, HMAC protects lookup and proof values, BCrypt hashes passwords, and authenticated sessions use expiring HttpOnly cookies. The local server binds to loopback and uses HTTP by default.

Run the backend integration suite with `mvn test` from `backend`. It exercises REST endpoints with an isolated H2 database. The production Angular build type-checks the UI and templates.

## Production dependencies

This release is a localhost sandbox with simulated funds. A production payment service needs contracted UPI and bank APIs, provider-backed ownership verification, settlement reconciliation, authenticated callbacks, real SMS and email, and push delivery with the app closed. Provider contracts and credentials were unavailable for this implementation.

Production also requires an agreed TLS deployment and key-management plan, tested Oracle migrations and recovery, monitored availability and capacity, staffed 24/7 support channels, and assessments for the financial, payment-security and data-protection obligations applicable to the deployment. The BRD’s three-second processing, 99.9% availability and 10,000-concurrent-user targets are acceptance criteria for the production environment.
