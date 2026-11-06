import java.lang.reflect.Method;

/** Executes without Maven so feature boundaries can be checked before the full build. */
public class DisabledFeaturesTest {
    public static void main(String[] args) throws Exception {
        Class<?> policy;
        try {
            policy = Class.forName("com.java110.utils.config.DisabledFeatures");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Disabled feature requests are not guarded", e);
        }
        Method request = policy.getMethod("isDisabledRequest", String.class);
        String[] blocked = {
            "/app/renting.queryRentingPools", "/rentingApi/queryRentingPools",
            "/app/junkRequirement.saveJunkRequirement", "/app/product/queryProducts",
            "/app/groupBuy/queryGroupBuys", "/app/storeOrder/queryStoreOrders",
            "/app/storeCart/queryStoreCarts", "/shopApi/queryShops",
            "/app/ownerRepair.saveOwnerRepair", "/api/inspection.listInspectionPlans",
            "/callComponent/purchaseApply/list", "/app/machineApi/openDoor",
            "/app/applicationKey.listApplicationKeys", "/app/complaint.listComplaints",
            "/app/notice.listNotices", "/app/activities.listActivitiess",
            "/callComponent/resourceStoreManage/list", "/app/visit.listVisits",
            "/app/%6fwnerRepair.saveOwnerRepair", "/APP/REPAIR.listRepairSettings",
            "/callComponent/addMachine/save", "/callComponent/upload/addAdvert/upload",
            "/callComponent/reportRepair/list", "/app/auditUser.listAuditComplaints",
            "/app/reportFeeMonthStatistics/queryRepair", "/app/collection/resourceOut",
            "/app/machineTranslate.machineUploadFaceLog", "/app/machineTranslate.machineQueryUserInfo",
            "/app/ownersRepair.query", "/app/auditUser.listAuditHistoryComplaints",
            "/app/bigScreen.getAssetInspection", "/app;x/repair.listRepairSettings",
            "/api;x/complaint.listComplaints", "/callComponent;x/addMachine/save",
            "/app/index.queryIndexTodoTask", "/app/auditUser.listAuditHistoryOrders",
            "/callComponent/myAuditOrders/list", "/callComponent/myAuditOrders/audit"
        };
        String[] retained = {
            "/callComponent/shops/query", "/callComponent/simplifyShopsHireLog/list",
            "/app/ownerApi/queryShopsHireLog", "/app/store.listStores",
            "/app/fee.listFee", "/app/payment/oweFeeNotify", "/app/payment/toPay",
            "/app/owner.uploadOwnerPhoto", "/app/owner.ownerRegisterWxPhoto",
            "/app/wechat/gateway", "/app/refreshToken", "/app/smallWeChat.listSmallWeChats",
            "/callComponent/wechatMenuManage/list", "/app/carInout.listCarInouts",
            "/app/parkingSpace.queryParkingSpacesByOwner", "/app/roomRenovation/query",
            "/callComponent/menu/getMenus", "/app/meterWater.queryMeterWaters",
            "/app/machine.uploadOwnerPhoto", "/app/machine/uploadOwnerPhoto",
            "/api/machineTranslate.machineGetFreeParkingSpace",
            "/app/machineTranslate.machineUploadCarLog", "/api/machineTranslate.machineRoadGateOpen",
            "/api/machineTranslate.machineHeartbeart", "/api/machineTranslate/machineGetTaskInfo",
            "/api/machineTranslate.machineCmdResult", "/machineApi/queryMachines",
            "/machineTranslateApi/queryMachineTranslates", "/machineTranslateApi/saveMachineTranslate"
        };
        for (String path : blocked) check((Boolean) request.invoke(null, path), "Must block " + path);
        for (String path : retained) check(!(Boolean) request.invoke(null, path), "Must retain " + path);
        Method task = policy.getMethod("isDisabledTask", String.class);
        check((Boolean) task.invoke(null, "generateInspectionTaskTemplate"), "Inspection task must stop");
        check((Boolean) task.invoke(null, "generatorGroupBuyBatchTemplate"), "Retired group buy task must stop");
        check((Boolean) task.invoke(null, "translateOwnerPhotoToMachineTemplate"), "Device sync must stop");
        check(!(Boolean) task.invoke(null, "generateBillProTemplate"), "Billing task must remain");
        check(!(Boolean) task.invoke(null, "publicWeChatPushMessageTemplate"), "Fee notification must remain");
        check(!(Boolean) task.invoke(null, "payFeeReceiptAdapt"), "Payment receipt must remain");
        check(!(Boolean) task.invoke(null, "carToMachineTemplate"), "Parking vehicle sync must remain");
        System.out.println("PASS: " + (blocked.length + retained.length + 7) + " feature boundary checks");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
