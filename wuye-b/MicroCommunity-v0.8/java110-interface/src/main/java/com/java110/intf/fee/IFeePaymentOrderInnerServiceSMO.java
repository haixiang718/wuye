package com.java110.intf.fee;

import com.java110.config.feign.FeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Internal only. Amounts are integer fen; no merchant secrets are stored. */
@FeignClient(name = "fee-service", configuration = {FeignConfiguration.class})
@RequestMapping("/paymentOrderApi")
public interface IFeePaymentOrderInnerServiceSMO {
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    Map<String,Object> create(@RequestBody Map<String,Object> request);
    @RequestMapping(value = "/query", method = RequestMethod.POST)
    Map<String,Object> query(@RequestBody Map<String,Object> request);
    @RequestMapping(value = "/confirm", method = RequestMethod.POST)
    Map<String,Object> confirm(@RequestBody Map<String,Object> request);
}
