package dev.ezpay;

class ApiError extends RuntimeException {
    final int status;

    ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    static ApiError invalid(String message) {
        return new ApiError(400, message);
    }

    static ApiError missing() {
        return new ApiError(404, "This record could not be found.");
    }

    static String required(String value, int max, String label) {
        String cleaned = optional(value, max, label);
        if (cleaned.isBlank()) throw invalid(label + " is required.");
        return cleaned;
    }

    static String optional(String value, int max, String label) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.length() > max) throw invalid(label + " is too long.");
        return cleaned;
    }
}
