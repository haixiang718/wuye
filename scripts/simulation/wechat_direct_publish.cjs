'use strict';
// Real local property-admin publish button; never sends a message or payment.
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('../../.tools/browser/node_modules/playwright-core');
const root=path.resolve(__dirname,'../..');
const folder=path.join(root,'.runtime/wechat-direct-flow');
const fixture=JSON.parse(fs.readFileSync(path.join(folder,'fixture.json'),'utf8'));
if(!/^WXD[a-f0-9]{12}$/.test(fixture.prefix)||fixture.label!=='LOCAL WECHAT DIRECT FLOW ONLY - DO NOT PAY'||!fixture.billId)throw new Error('Unexpected fixture');
(async()=>{
  const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
  try{
    const context=await browser.newContext();
    await context.addCookies(JSON.parse(fs.readFileSync(path.join(root,'.runtime/browser-cookies.json'),'utf8').replace(/^\uFEFF/,'')));
    const page=await context.newPage();page.on('dialog',dialog=>dialog.accept());
    await page.goto('http://127.0.0.1:3000/admin.html',{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity());
    if(await page.evaluate(()=>vc.getCurrentCommunity().communityId)!==fixture.communityId)throw new Error('Wrong admin community');
    await page.evaluate(()=>window.location.hash='#/pages/property/billManage');
    const row=page.locator('tbody tr').filter({hasText:fixture.billId});
    await row.getByText('审核发布',{exact:true}).waitFor({timeout:30000});
    await row.getByText('审核发布',{exact:true}).click();
    await row.getByText('已审核发布',{exact:true}).waitFor({timeout:30000});
    fs.writeFileSync(path.join(folder,'publication.json'),JSON.stringify({passed:true,actualAdminButton:true,notifications:0,payments:0},null,2));
    console.log('Actual local admin button published only the marked test bill; no messages or payments.');
  }finally{await browser.close();}
})().catch(()=>{console.error('Local fixture publication failed; inspect private runtime and preserve evidence.');process.exitCode=2;});
