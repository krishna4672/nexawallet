package com.nexawallet.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NexaWallet Platform - Main Application Bootstrap.
 * Enterprise Digital Wallet engineered with Spring Boot 3 & Java 21.
 */
@SpringBootApplication
@EnableScheduling
public class NexaWalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexaWalletApplication.class, args);
    }
}
