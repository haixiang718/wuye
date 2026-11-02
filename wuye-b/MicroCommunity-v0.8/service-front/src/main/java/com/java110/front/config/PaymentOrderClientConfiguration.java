package com.java110.front.config;

import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/** Front otherwise scans only order clients; register the payment client explicitly. */
@Configuration
@EnableFeignClients(clients = IFeePaymentOrderInnerServiceSMO.class)
public class PaymentOrderClientConfiguration {
}
