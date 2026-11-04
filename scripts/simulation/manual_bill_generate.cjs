'use strict';
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('../../.tools/browser/node_modules/playwright-core');
const root=path.resolve(__dirname,'../..'),folder=path.join(root,'.runtime/manual-bill-flow');
const fixture=JSON.parse(fs.readFileSync(path.join(folder,'fixture.json'),'utf8'));
if(!/^WXD[a-f0-9]{12}$/.test(fixture.prefix)||fixture.label!=='LOCAL WECHAT DIRECT FLOW ONLY - DO NOT PAY'||fixture.ids.config!==fixture.prefix+'C')throw new Error('Unexpected fixture provenance');
const report={checks:[],errors:[],notifications:0,payments:0};
function check(name,passed){report.checks.push({name,passed:!!passed});if(!passed)throw new Error('Manual generation assertion failed');}
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 try{
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  await context.addCookies(JSON.parse(fs.readFileSync(path.join(root,'.runtime/browser-cookies.json'),'utf8').replace(/^\uFEFF/,'')));
  const page=await context.newPage();page.setDefaultTimeout(30000);page.on('pageerror',error=>report.errors.push(error.stack));page.on('dialog',dialog=>dialog.accept());
  // Follow normal admin navigation: nav first resolves the authorized community.
  await page.goto('http://127.0.0.1:3000/admin.html',{waitUntil:'networkidle'});
  await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity());
  await page.goto('http://127.0.0.1:3000/admin.html#/pages/property/billManage',{waitUntil:'networkidle'});
  await page.waitForFunction(()=>window.vc&&vc.component&&vc.component.billManageInfo);
  check('expected_admin_community',await page.evaluate(()=>vc.getCurrentCommunity().communityId)===fixture.communityId);
  await page.getByRole('button',{name:'生成待审核账单',exact:true}).click();
  await page.locator('#bill-generation-name').fill(fixture.LABEL||'LOCAL WECHAT DIRECT FLOW ONLY - DO NOT PAY');
  await page.locator('#bill-generation-type').selectOption('005');
  await page.getByRole('button',{name:'查询收费项目',exact:true}).click();
  await page.locator('#bill-generation-config option[value="'+fixture.ids.config+'"]').waitFor({state:'attached'});
  await page.locator('#bill-generation-config').selectOption(fixture.ids.config);
  for(let round=0;round<2;round++){
   const responsePromise=page.waitForResponse(r=>r.url().includes('/app/fee.generateBill')&&r.request().method()==='POST');
   await page.getByRole('button',{name:'确认生成待审核账单',exact:true}).click();
   const response=await responsePromise;let data;try{data=await response.json();}catch{}
   report.lastGenerationResponse={status:response.status(),data};
   check(round?'repeat_returns_existing_draft':'button_creates_draft',response.status()===200&&data?.code===0&&data?.data?.reviewState==='DRAFT'&&data?.data?.existing===!!round);
   check(round?'repeat_preserves_bill_id':'server_bill_identifier_present',round?data.data.billId===report.billId:!!data.data.billId);
   if(!round)report.billId=data.data.billId;
   await page.locator('tbody tr').filter({hasText:report.billId}).getByText('待审核',{exact:true}).waitFor();
   check(round?'repeat_keeps_review_button':'new_bill_is_visible_for_review',await page.locator('tbody tr').filter({hasText:report.billId}).getByRole('button',{name:'审核并推送',exact:true}).count()===1);
  }
  check('no_page_errors',report.errors.length===0);
  check('no_horizontal_overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
  await page.screenshot({path:path.join(folder,'draft-generated.png'),fullPage:true});report.passed=true;
 }catch(error){report.failure=error.stack;throw error;}
 finally{await browser.close();fs.writeFileSync(path.join(folder,'browser.json'),JSON.stringify(report,null,2));}
 console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,notifications:0,payments:0}));
})().catch(()=>{console.error('Real local admin generation failed; inspect scoped private report.');process.exitCode=2;});
