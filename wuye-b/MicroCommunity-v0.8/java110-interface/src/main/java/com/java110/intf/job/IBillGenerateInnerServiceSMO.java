package com.java110.intf.job;

import com.alibaba.fastjson.JSONObject;
import com.java110.config.feign.FeignConfiguration;
import com.java110.dto.fee.FeeConfigDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/** Internal single-configuration draft generation; never schedules or publishes bills. */
@FeignClient(name = "job-service", configuration = {FeignConfiguration.class})
@RequestMapping("/billGenerateApi")
public interface IBillGenerateInnerServiceSMO {
    @RequestMapping(value = "/generate", method = RequestMethod.POST)
    JSONObject generate(@RequestBody FeeConfigDto input);
}
