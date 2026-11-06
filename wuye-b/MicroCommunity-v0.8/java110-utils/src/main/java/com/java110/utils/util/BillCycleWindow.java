package com.java110.utils.util;

import java.util.Calendar;
import java.util.Date;

/** Preserve legacy rolling windows until the customer defines calendar billing periods. */
public final class BillCycleWindow {
    private BillCycleWindow() { }

    public static Date start(String billType, Date now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        if ("002".equals(billType) || "004".equals(billType) || "005".equals(billType)) {
            calendar.add(Calendar.MONTH, -1);
        } else if ("003".equals(billType)) {
            calendar.add(Calendar.DATE, -1);
        } else if ("001".equals(billType)) {
            calendar.add(Calendar.YEAR, -1);
        } else {
            throw new IllegalArgumentException("Unsupported bill cycle");
        }
        return calendar.getTime();
    }
}
