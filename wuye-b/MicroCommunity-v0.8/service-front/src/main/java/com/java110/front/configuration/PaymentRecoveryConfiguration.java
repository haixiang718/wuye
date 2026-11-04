package com.java110.front.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="java110.payment.v3.recovery.enabled",havingValue="true")
public class PaymentRecoveryConfiguration { }
