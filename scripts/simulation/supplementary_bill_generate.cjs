'use strict';
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('../../.tools/browser/node_modules/playwright-core');
const root=path.resolve(__dirname,'../..'),folder=path.join(root,'.runtime/supplementary-bill-flow');
const f=JSON.parse(fs.readFileSync(path.join(folder,'fixture.json'),'utf8'));
const [step,existing,supplemental]=process.argv.slice(2);
if(!/^[a-z0-9-]+$/.test(step)||!/^WXD[a-f0-9]{12}$/.test(f.prefix)||f.label!=='LOCAL WECHAT DIRECT FLOW ONLY - DO NOT PAY')throw Error('Unexpected fixture');
const report={checks:[],messagesSent:0,payments:0,pageErrors:[]};
function check(name,passed){report.checks.push({name,passed:!!passed});if(!passed)throw Error(name);}
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 try{
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  const cookies=JSON.parse(fs.readFileSync(path.join(root,'.runtime/browser-cookies.json'),'utf8').replace(/^\uFEFF/,''));
  await context.addCookies(cookies.map(c=>({...c,domain:'admin.jiaanfuwu.cn',secure:true})));
  const page=await context.newPage();page.setDefaultTimeout(45000);page.on('pageerror',e=>report.pageErrors.push(e.message));
  await page.goto('https://admin.jiaanfuwu.cn/admin.html',{waitUntil:'networkidle',timeout:90000});
  await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity());
  await page.goto('https://admin.jiaanfuwu.cn/admin.html#/pages/property/billManage',{waitUntil:'networkidle',timeout:90000});
  await page.waitForFunction(()=>window.vc&&vc.component.billManageInfo);
  check('expected_community',await page.evaluate(()=>vc.getCurrentCommunity().communityId)===f.communityId);
  await page.getByRole('button',{name:'生成待审核账单',exact:true}).click();
  await page.locator('#bill-generation-name').fill(f.label);
  await page.locator('#bill-generation-type').selectOption('005');
  await page.getByRole('button',{name:'查询收费项目',exact:true}).click();
  await page.locator('#bill-generation-config option[value="'+f.ids.config+'"]').waitFor({state:'attached'});
  await page.locator('#bill-generation-config').selectOption(f.ids.config);
  const response=page.waitForResponse(r=>r.url().includes('/app/fee.generateBill')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'确认生成待审核账单',exact:true}).click();
  const reply=await (await response).json();
  check('generation_success',reply.code===0);
  check('expected_new_or_existing',reply.data.existing===(existing==='1'));
  check('expected_supplement_flag',reply.data.supplemental===(supplemental==='1'));
  report.billId=reply.data.billId;
  await page.locator('tbody tr').filter({hasText:report.billId}).waitFor();
  check('returned_bill_visible_in_list',true);
  if(existing!=='1'){
   check('new_bill_is_draft',reply.data.reviewState==='DRAFT');
   check('manual_review_button_present',await page.locator('tbody tr').filter({hasText:report.billId}).getByRole('button',{name:'审核并推送',exact:true}).count()===1);
  }
  check('no_page_errors',report.pageErrors.length===0);report.passed=true;
 }catch(e){report.failure=e.message;throw e;}
 finally{fs.writeFileSync(path.join(folder,step+'.json'),JSON.stringify(report,null,2));await browser.close();}
 console.log(JSON.stringify({step,passed:true,checks:report.checks.length}));
})().catch(e=>{console.error('Scoped supplementary browser check failed: '+e.message);process.exitCode=1;});
