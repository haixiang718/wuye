package com.java110.utils.config;

import java.net.URLDecoder;
import java.util.Locale;

/** Product scope agreed with the customer. Shared data types remain available. */
public final class DisabledFeatures {
    private static final String[] RESOURCES = {
        "repair", "ownerrepair", "ownersrepair", "inspection", "excuteinspection",
        "purchase", "collection", "resource", "storehouse", "userstorehouse", "allocationstorehouse",
        "allocationuserstorehouse", "poutofstorage", "machine", "applicationkey",
        "openDoor", "complaint", "notice", "advert", "activities", "activity",
        "visit", "communitynotice", "renting", "junkrequirement", "groupbuy",
        "product", "storeorder", "storecart", "shopvip", "buyshop", "appraise", "goods"
    };

    // Keep component codes aligned with public/js/local-features.js.
    private static final java.util.Set<String> COMPONENTS = new java.util.HashSet<>(
        java.util.Arrays.asList((
            "simplifyowneraccesscontol,indexopendooranalysis,inandoutstep,chooseinandouttype,activitiesbeautifulstaffmanage,activitiesmanage,activitiesrulemanage,activitiestypemanage,addactivitiesbeautifulstaff,addactivitiesrule,addactivitiestype,addactivitiesview," +
            "addadvert,addapplicationkey,addcomplaintstep,addinspectionplan,addinspectionplanstaff,addinspectionpoint,addinspectionroute,additemoutstep,addmachine,addmachineauth,addmachinetranslate,addnotice," +
            "addnoticeview,addownerrepair,addpurchaseapplystep,addpurchaseapplyview,addpurchaseapplyview2,addrepairsetting,addresourcequantity,addresourcestore,addresourcestorespecification,addresourcestoretype,addresourcesupplier,addroomcomplaint," +
            "addroomrepair,addstorehouse,addvisit,addvisitcase,addvisitspace,advertmanage,advertvedioview,allocationstorehouse,allocationstorehouseapplymanage,allocationstorehouseauditorders,allocationstorehousedetail,allocationstorehousedetailed," +
            "allocationstorehousehistoryauditorders,allocationstorehousemanage,allocationuserstorehousemanage,applicationkeymanage,appraiserepair,auditapplicationkeymanage,chooseactivities,chooseactivitiesbeautifulstaff,chooseactivitiesrule,chooseactivitiestype,chooseadvert,chooseapplicationkey," +
            "choosecomplaint,chooseinspectionplan,chooseinspectionpoint,chooseinspectionroute,chooseinspectionroutepoint,choosemachine,choosemachineauth,choosemachinetranslate,chooseownerrepair,choosepurchaseapply,chooserepairsetting,chooseresourcestaff," +
            "chooseresourcestore,chooseresourcestore2,chooseresourcestore3,chooseresourcestorespecification,chooseresourcesupplier,choosesingleresource,choosestorehouse,complaintdetail,complaintmanage,deleteactivities,deleteactivitiesbeautifulstaff,deleteactivitiesrule," +
            "deleteactivitiestype,deleteadvert,deleteapplicationkey,deletecomplaint,deleteinspectionplan,deleteinspectionplanstaff,deleteinspectionpoint,deleteinspectionroute,deleteinspectionroutepoint,deleteitemout,deletemachine,deletemachineauth," +
            "deletemachinetranslate,deletenotice,deleteownerrepair,deletepurchaseapply,deleterepairsetting,deleterepairtypeuser,deleteresourcestore,deleteresourcestorespecification,deleteresourcestoretype,deleteresourcesupplier,deletestorehouse,deletestorehousemanage," +
            "dispatchrepair,editactivitiesbeautifulstaff,editactivitiesrule,editactivitiestype,editactivitiesview,editadvert,editapplicationkey,editcomplaint,editinspectionplan,editinspectionpoint,editinspectionroute,editmachine," +
            "editmachineauth,editmachinetranslate,editnoticeview,editownerrepair,editpurchaseapply,editrepairsetting,editrepairtypeuser,editresourcestore,editresourcestorespecification,editresourcestoretype,editresourcesupplier,editstorehouse," +
            "editvisit,finishrepair,forcefinishrepair,inspectionplanmanage,inspectionplanstaffmanage,inspectionplanstate,inspectionpointmanage,inspectionroutemanage,inspectionroutepointmanage,inspectionrouteselect2,inspectiontaskdetail,inspectiontaskmanage," +
            "iteminmanage,itemout,itemoutmanage,machineauthmanage,machinemanage,machinerecorddetail,machinerecordmanage,machineselect2,machinestate,machinetranslatemanage,machinevistorphotomanage,myauditcomplaints," +
            "myaudithistorycomplaints,myitemoutaudithistoryorders,myitemoutauditorders,myrepairdispatchmanage,myresourcestoremanage,noticedetail,noticemanage,ownerrepairdetail,ownerrepairmanage,printpurchaseapply,printrepairdetail,purchaseapplydetail," +
            "purchaseapplydetailmanage,purchaseapplymanage,purchaseapprovers,repairdispatchmanage,repairdispatchstep,repairforcefinishmanage,repairpoolmanage,repairreturnvisit,repairsettingmanage,repairtypeuser,reportrepair,resourceenterdetailmanage," +
            "resourceentermanage,resourceoutmanage,resourcestoremanage,resourcestorespecificationmanage,resourcestoretypemanage,resourcestoreuserecordmanage,resourcesuppliermanage,resubmitcomplaint,simplifyownercomplaint,simplifyownerrepair,storehousemanage,transfergoodsstep," +
            "viewactivitiesbeautifulstaffinfo,viewactivitiesinfo,viewactivitiesruleinfo,viewactivitiestypeinfo,viewadvertinfo,viewapplicationkeyinfo,viewcomplaintinfo,viewinspectionplaninfo,viewinspectionpointinfo,viewinspectionrouteinfo,viewmachineauthinfo,viewmachineinfo," +
            "viewmachinerecordinfo,viewmachinetranslateinfo,viewownerrepairinfo,viewpurchaseapplyinfo,viewrepairsettinginfo,viewresourcestaffinfo,viewresourcestoreinfo,viewresourcestoreinfo2,viewresourcestoreinfo3,viewresourcestorespecificationinfo,viewresourcesupplierinfo,viewstorehouseinfo," +
            "viewvisitinfo,visitforowner,visitmanage,visitownerrepair,writeadvertmachine,addcomplaintstepbinding,itemoutbinding,addallocationuserstorehouse,repairdispatchstepbinding,myrepairdispatch,updateinspectionplanstate,editnotice"
        ).split(",")));

    private static final java.util.Set<String> V2_COMPONENTS = new java.util.HashSet<>(
        java.util.Arrays.asList((
            "addapplicationkey,addcomplaintstep,addinspectionplan,addinspectionplanstaff,addinspectionpoint,addinspectionroute,addjunkrequirement,addmachine,addmachineauth," +
            "addmachinetranslate,addmaincategory,addmaincategoryproduct,addownerrepair,addproduct,addproductcategory,addproductspec,addrentingappointment,addrentingconfig," +
            "addrentingpool,addrepairsetting,addroomcomplaint,addroomrepair,addvisit,addvisitcase,addvisitspace,applicationkeymanage,appraiserepair," +
            "auditapplicationkeymanage,auditopenshop,auditshopcommunity,chooseapplicationkey,choosecomplaint,chooseinspectionplan,chooseinspectionpoint,chooseinspectionroute,chooseinspectionroutepoint," +
            "choosejunkrequirement,chooselabelproduct,choosemachine,choosemachineauth,choosemachinetranslate,choosemaincategory,choosemaincategoryproduct,chooseownerrepair,chooseproduct," +
            "chooseproductcategory,chooseproductspec,chooserentingappointment,chooserentingconfig,chooserentingpool,chooserepairsetting,complaintdetail,complaintmanage,confirmrenting," +
            "deleteapplicationkey,deletecomplaint,deleteinspectionplan,deleteinspectionplanstaff,deleteinspectionpoint,deleteinspectionroute,deleteinspectionroutepoint,deletejunkrequirement,deletelabelproduct," +
            "deletemachine,deletemachineauth,deletemachinetranslate,deletemaincategory,deletemaincategoryproduct,deleteownerrepair,deleteproduct,deleteproductcategory,deleteproductspec," +
            "deleterentingappointment,deleterentingconfig,deleterentingpool,deleterepairsetting,deleterepairtypeuser,dispatchrepair,editapplicationkey,editcomplaint,editinspectionplan," +
            "editinspectionpoint,editinspectionroute,editjunkrequirement,editmachine,editmachineauth,editmachinetranslate,editmaincategory,editmaincategoryproduct,editownerrepair," +
            "editproduct,editproductcategory,editproductspec,editrentingappointment,editrentingconfig,editrentingpool,editrepairsetting,editrepairtypeuser,editvisit," +
            "finishrepair,forcefinishrepair,indexopendooranalysis,inspectionplanmanage,inspectionplanstaffmanage,inspectionplanstate,inspectionpointmanage,inspectionroutemanage,inspectionroutepointmanage," +
            "inspectionrouteselect2,inspectiontaskdetail,inspectiontaskmanage,junkrequirementmanage,machineauthmanage,machinemanage,machinerecorddetail,machinerecordmanage,machineselect2," +
            "machinestate,machinetranslatemanage,machinevistorphotomanage,maincategorymanage,maincategoryproductmanage,myauditcomplaints,myaudithistorycomplaints,myrepairdispatchmanage,ownerrepairdetail," +
            "ownerrepairmanage,printrepairdetail,productcategorymanage,productmanage,productspecmanage,recommendgoodsmanage,rentingappointmentmanage,rentingauditmanage,rentingconfigmanage," +
            "rentingdetail,rentinghistorymanage,rentingpay,rentingpoolmanage,repairdispatchmanage,repairdispatchstep,repairforcefinishmanage,repairpoolmanage,repairreturnvisit," +
            "repairsettingmanage,repairtypeuser,reportrepair,resubmitcomplaint,shopcommunity,shopmanage,simplifyowneraccesscontol,simplifyownercomplaint,simplifyownerrepair," +
            "storeordercartdetail,storeordercartmanage,storeordercartreturnmanage,viewapplicationkeyinfo,viewcomplaintinfo,viewinspectionplaninfo,viewinspectionpointinfo,viewinspectionrouteinfo,viewjunkrequirementinfo," +
            "viewmachineauthinfo,viewmachineinfo,viewmachinerecordinfo,viewmachinetranslateinfo,viewmaincategoryinfo,viewmaincategoryproductinfo,viewownerrepairinfo,viewproductcategoryinfo,viewproductinfo," +
            "viewproductspecinfo,viewrentingappointmentinfo,viewrentingconfiginfo,viewrentingpoolinfo,viewrepairsettinginfo,viewvisitinfo,visitforowner,visitmanage,visitownerrepair," +
            "writeadvertmachine"
        ).split(",")));

    private DisabledFeatures() { }

    public static boolean isDisabledRequest(String uri) {
        if (uri == null) return false;
        String path = uri;
        try {
            // Servlet containers and proxies may perform one decoding pass each.
            for (int i = 0; i < 3 && path.contains("%"); i++) {
                String decoded = URLDecoder.decode(path, "UTF-8");
                if (decoded.equals(path)) break;
                path = decoded;
            }
        } catch (Exception e) {
            return true;
        }
        path = path.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        // Spring MVC removes matrix parameters before selecting its controller.
        path = path.replaceAll(";[^/]*", "");
        String[] segments = path.split("/+" );
        int resourceIndex = 1;
        if (segments.length <= resourceIndex) return false;
        String prefix = segments[resourceIndex];
        if ("app".equals(prefix) || "api".equals(prefix) || "callcomponent".equals(prefix)
                || "flow".equals(prefix)) resourceIndex++;
        if (segments.length <= resourceIndex) return false;
        if ("upload".equals(segments[resourceIndex]) && segments.length > resourceIndex + 1) resourceIndex++;
        String operation = String.join("/", java.util.Arrays.copyOfRange(segments, resourceIndex, segments.length));
        if ("machine.uploadownerphoto".equals(operation) || "machine/uploadownerphoto".equals(operation)) return false;
        // Parking shares its protocol and internal storage with the device module.
        String protocol = operation.replace('/', '.');
        if (protocol.matches("machinetranslate\\.(machinegetfreeparkingspace|machineuploadcarlog|machineroadgateopen|machineheartbeart|machinegettaskinfo|machinecmdresult)")) return false;
        if (resourceIndex == 1 && (operation.matches("machineapi/(querymachines|querymachinescount|updatemachinestate)")
                || operation.matches("machinetranslateapi/(querymachinetranslates|querymachinetranslatescount|updatemachinetranslatestate|savemachinetranslate)"))) return false;
        if ("audituser.listauditcomplaints".equals(operation)
                || "index.queryindextodotask".equals(operation)
                || "index/queryindextodotask".equals(operation)
                || "audituser.listaudithistoryorders".equals(operation)
                || "audituser/listaudithistoryorders".equals(operation)
                || "myauditorders/list".equals(operation)
                || "myauditorders/audit".equals(operation)
                || "audituser.listaudithistorycomplaints".equals(operation)
                || "audituser.listitemoutaudithistoryorders".equals(operation)
                || "audituser.listitemoutauditorders".equals(operation)
                || "reportfeemonthstatistics/queryrepair".equals(operation)
                || "reportfeemonthstatistics.queryrepair".equals(operation)
                || "nav/getnavdata".equals(operation)
                || operation.matches("bigscreen[/.](getassetsrepair|getassetopendoor|getassetinspection)")) return true;
        if (protocol.equals("owner.listownermachines") || protocol.equals("ownerapi.visitorrecord")
                || protocol.equals("payment.rentingtopay") || protocol.equals("payment.rentingnotify")
                || protocol.equals("goods.unifieldorder")) return true;
        String resource = segments[resourceIndex].split("[.;]", 2)[0];
        if ("shop".equals(resource) || "shopapi".equals(resource)) return true;
        if (COMPONENTS.contains(resource) || V2_COMPONENTS.contains(resource)) return true;
        for (String disabled : RESOURCES) {
            if (resource.startsWith(disabled.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static boolean isDisabledTask(String beanName) {
        if (beanName == null) return false;
        String name = beanName.toLowerCase(Locale.ROOT);
        if ("cartomachinetemplate".equals(name)) return false;
        return name.contains("inspection") || name.contains("repair") || name.contains("groupbuy")
                || name.contains("purchase") || name.contains("tomachine")
                || name.contains("photomachine") || name.contains("applicationkey");
    }
}
