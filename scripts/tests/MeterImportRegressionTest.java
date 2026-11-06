import com.alibaba.fastjson.*;
import com.java110.core.context.IPageData;
import com.java110.entity.component.ComponentValidateResult;
import com.java110.front.smo.assetImport.impl.ImportMeterWaterFeeSMOImpl;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real XLSX parser with a substituted downstream boundary; never imports customer charges. */
public class MeterImportRegressionTest {
    static class Importer extends ImportMeterWaterFeeSMOImpl {
        JSONObject submitted;
        boolean unavailable;
        @Override protected ComponentValidateResult validateStoreStaffCommunityRelationship(IPageData p, RestTemplate r) {
            return new ComponentValidateResult("test-store","800900000003","test-community","test-staff","Test");
        }
        @Override public ResponseEntity<String> callCenterService(RestTemplate r,IPageData p,String body,String url,HttpMethod method) {
            submitted=JSON.parseObject(body);
            if(unavailable) throw new IllegalStateException("Unknown table contract_room; password=PRIVATE; SQL select * from contract_room");
            return new ResponseEntity<>("{\"code\":0,\"msg\":\"success\"}",HttpStatus.OK);
        }
    }
    static IPageData page() {
        return (IPageData)Proxy.newProxyInstance(IPageData.class.getClassLoader(),new Class[]{IPageData.class},(p,m,a)->{
            if(m.getName().equals("getReqData"))return "{\"communityId\":\"test-community\",\"feeTypeCd\":\"888800010016\",\"configId\":\"test-electric\"}";
            return null;
        });
    }
    static MultipartFile upload(byte[] bytes) {
        return (MultipartFile)Proxy.newProxyInstance(MultipartFile.class.getClassLoader(),new Class[]{MultipartFile.class},(p,m,a)->{
            switch(m.getName()) {
                case "getInputStream": return new ByteArrayInputStream(bytes);
                case "getBytes": return bytes;
                case "getSize": return (long)bytes.length;
                case "isEmpty": return bytes.length==0;
                case "getName": return "uploadFile";
                case "getOriginalFilename": return "test.xlsx";
                case "getContentType": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                default:return null;
            }
        });
    }
    static byte[] workbook(boolean complete) throws Exception {
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet=workbook.createSheet("房屋费用信息");
            sheet.createRow(0).createCell(0).setCellValue("说明");
            sheet.createRow(1).createCell(0).setCellValue("楼栋编号");
            org.apache.poi.ss.usermodel.Row row=sheet.createRow(2);
            String[] data={"T1","1","TEST101","0","2026-11-01","12","2026-11-02","测试"};
            for(int i=0;i<data.length;i++)row.createCell(i).setCellValue(!complete&&i==5?"":data[i]);
            workbook.write(out);return out.toByteArray();
        }
    }
    @Test public void acceptsNormalTextDatesAndReadings() throws Exception {
        Importer importer=new Importer();
        assertEquals(HttpStatus.OK,importer.importExcelData(page(),upload(workbook(true))).getStatusCode());
        JSONObject row=importer.submitted.getJSONArray("importMeteWaterFees").getJSONObject(0);
        assertEquals("2026-11-01",row.getString("preReadingTime"));
        assertEquals("2026-11-02",row.getString("curReadingTime"));
        assertEquals("12",row.getString("curDegrees"));
    }
    @Test public void parsesUserFilledWorkbookWithoutSendingIt() throws Exception {
        String file=System.getProperty("meter.user.file");
        org.junit.Assume.assumeNotNull(file);
        Importer importer=new Importer();
        assertEquals(HttpStatus.OK,importer.importExcelData(page(),upload(Files.readAllBytes(Paths.get(file)))).getStatusCode());
        JSONArray rows=importer.submitted.getJSONArray("importMeteWaterFees");
        assertEquals(3,rows.size());
        for(int i=0;i<3;i++) {
            assertEquals("2026-11-01",rows.getJSONObject(i).getString("preReadingTime"));
            assertEquals("2026-11-02",rows.getJSONObject(i).getString("curReadingTime"));
            assertEquals(String.valueOf(10+i),rows.getJSONObject(i).getString("curDegrees"));
        }
    }
    @Test public void reportsMissingInputWithoutCallingBackend() throws Exception {
        Importer importer=new Importer();
        ResponseEntity<String> result=importer.importExcelData(page(),upload(workbook(false)));
        assertEquals(HttpStatus.BAD_REQUEST,result.getStatusCode());
        assertTrue(result.getBody().contains("本期度数"));assertNull(importer.submitted);
    }
    @Test public void infrastructureFailureIsNotBlamedOnSpreadsheetAndDoesNotLeakInternals() throws Exception {
        Importer importer=new Importer();importer.unavailable=true;
        ResponseEntity<String> result=importer.importExcelData(page(),upload(workbook(true)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,result.getStatusCode());
        assertFalse(result.getBody().contains("模板数据有误"));
        assertFalse(result.getBody().contains("PRIVATE"));assertFalse(result.getBody().contains("contract_room"));
        assertTrue(result.getBody().contains("核对导入结果"));
    }
    @Test public void unreadableWorkbookReturnsConciseValidationError() throws Exception {
        Importer importer=new Importer();
        ResponseEntity<String> result=importer.importExcelData(page(),upload(new byte[]{1,2,3}));
        assertEquals(HttpStatus.BAD_REQUEST,result.getStatusCode());assertNull(importer.submitted);
        assertTrue(result.getBody().length()<180);
    }
}
