package dev.ezpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EzPay {
    public static void main(String[] args) {
        SpringApplication.run(EzPay.class, args);
    }
}
