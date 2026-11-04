package com.java110.intf.fee;

import com.java110.config.feign.FeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

/** Internal only. Amounts are integer fen; no merchant secrets are stored. */
@FeignClient(name = "fee-service", configuration = {FeignConfiguration.class})
@RequestMapping("/paymentOrderApi")
public interface IFeePaymentOrderInnerServiceSMO {
    /** Optional trusted maxTotalFee is a positive integer fen cap, checked before create or reuse. */
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    Map<String,Object> create(@RequestBody Map<String,Object> request);
    @RequestMapping(value = "/query", method = RequestMethod.POST)
    Map<String,Object> query(@RequestBody Map<String,Object> request);
    @RequestMapping(value = "/confirm", method = RequestMethod.POST)
    Map<String,Object> confirm(@RequestBody Map<String,Object> request);
    /**
     * Merchant-scoped CREATED V3 orders, at most 50. Without afterOrderId, oldest first.
     * With afterOrderId (empty string starts a scan), return order IDs greater than the
     * cursor in ascending ID order. Advance even when provider recovery fails; reset
     * to empty after an empty page. Cursors contain 1-32 ASCII letters/digits/_/-.
     */
    @RequestMapping(value = "/pendingV3", method = RequestMethod.POST)
    List<Map<String,Object>> pendingV3(@RequestBody Map<String,Object> request);
    /** Trusted caller only, after a signed provider query confirms CLOSED. */
    @RequestMapping(value = "/close", method = RequestMethod.POST)
    Map<String,Object> close(@RequestBody Map<String,Object> request);
}
