'use strict';
// Synthetic identity and mocked API only. Never creates an actual payment order.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('../../.tools/browser/node_modules/playwright-core');
const folder=path.resolve(__dirname,'../../.runtime/selective-payment-check');fs.mkdirSync(folder,{recursive:true});
const base='http://127.0.0.1:8000';
const owner={ownerId:'synthetic-owner',memberId:'synthetic-owner',userId:'synthetic-user',communityId:'synthetic-community',communityName:'界面测试小区',state:'12000',appUserName:'界面测试业主'};
const bills=[
  {feeId:'fee-a',oweId:'owe-a',billId:'bill-a',feeName:'物业服务费（2026年11月）',feePrice:'1234.50',billSnapshot:true,endTime:'2026-11-01 00:00:00',feeEndTime:'2026-11-01 00:00:00',deadlineTime:'2026-12-01 00:00:00'},
  {feeId:'fee-b',oweId:'owe-b',billId:'bill-b',feeName:'公共区域电费',feePrice:'0.01',billSnapshot:true,endTime:'2026-11-01 00:00:00',feeEndTime:'2026-11-01 00:00:00',deadlineTime:'2026-12-01 00:00:00'}
];
let lastPage;
(async()=>{
  const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
  const checks=[];
  try {
    for(const width of [360,390,430]) {
      const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage();lastPage=page;
      const errors=[],submissions=[];page.on('pageerror',e=>errors.push(e.message));
      await page.route('**/wechat-test/config.js',route=>route.fulfill({contentType:'application/javascript',body:'window.__WUYE_WECHAT_TEST__={appId:"synthetic-app",communityId:"synthetic-community",paymentEnabled:true};'}));
      await page.route('**/app/**',route=>{
        const url=route.request().url();let body={code:0,data:[]};
        if(url.includes('payment/toOweFeePay')){submissions.push(route.request().postDataJSON());body={code:1,msg:'Synthetic selection verification; provider not called'};}
        else if(url.includes('queryRoomsByOwner'))body={code:0,rooms:[{roomId:'synthetic-room',floorNum:'3',unitNum:'2',roomNum:'101',builtUpArea:80}]};
        else if(url.includes('fee.listFee'))body={code:0,total:2,fees:bills};
        else if(url.includes('listOweFees'))body={code:0,total:2,data:bills};
        else if(url.includes('listAppUserBindingOwners'))body={code:0,total:1,data:[owner]};
        return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
      });
      await page.goto(base+'/#/pages/index/index');await page.getByText('物业费',{exact:true}).waitFor();
      await page.evaluate(owner=>{
        uni.setStorageSync('owner_wechat_identity',{wechatSession:'synthetic-only',hasBinding:true,expiresAt:Date.now()+3600000});
        uni.setStorageSync('owner_verified_bindings',[owner]);uni.setStorageSync('ownerInfo',owner);uni.setStorageSync('userInfo',JSON.stringify(owner));
        uni.setStorageSync('loginFlag',{sessionKey:owner.userId,expireTime:Date.now()+3600000});uni.setStorageSync('token','synthetic-layout-only');
        uni.setStorageSync('currentCommunityInfo',{communityId:owner.communityId,communityName:owner.communityName});uni.$emit('owner-wechat-session-changed');
      },owner);
      await page.goto(base+'/#/pages/roomFeeListNew/roomFeeListNew');await page.getByText('¥1234.50',{exact:true}).waitFor();
      const listSubmit=page.getByText('缴纳所选',{exact:true});assert.ok(await listSubmit.getAttribute('disabled')!==null);
      await page.locator('.bill-check uni-checkbox').nth(1).click();
      await page.locator('.bill-summary').getByText('合计 ¥0.01',{exact:true}).waitFor();assert.ok(page.url().includes('roomFeeListNew'));
      const checkboxStyle=await page.locator('.bill-check uni-checkbox').nth(1).evaluate(el=>{
        const input=el.querySelector('.uni-checkbox-input'),styles=getComputedStyle(input,'::before');
        return {html:el.outerHTML,pseudo:{content:styles.content,color:styles.color,fontFamily:styles.fontFamily,display:styles.display,opacity:styles.opacity}};
      });
      assert.equal(checkboxStyle.pseudo.display,'block');
      fs.writeFileSync(path.join(folder,'checkbox-style-'+width+'.json'),JSON.stringify(checkboxStyle,null,2));
      await page.screenshot({path:path.join(folder,'list-'+width+'.png'),fullPage:true});
      await listSubmit.click();await page.locator('.selection-summary').getByText('合计 ¥0.01',{exact:true}).waitFor();
      await page.locator('.selection-summary').getByText('已选 1 项',{exact:true}).waitFor();
      assert.equal(await page.locator('.selection-check').count(),2);
      assert.equal(await page.getByText('2026-11-01 至 2026-12-01',{exact:true}).count(),2);
      await page.locator('.selection-check uni-checkbox').first().click();await page.getByText('合计 ¥1234.51',{exact:true}).waitFor();
      await page.locator('.selection-all uni-checkbox').click();await page.getByText('合计 ¥0.00',{exact:true}).waitFor();
      const checkoutSubmit=page.getByText('确认缴费',{exact:true});assert.ok(await checkoutSubmit.getAttribute('disabled')!==null);
      await page.locator('.selection-check uni-checkbox').nth(1).click();await page.getByText('合计 ¥0.01',{exact:true}).waitFor();
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
      await page.screenshot({path:path.join(folder,'checkout-'+width+'.png'),fullPage:true});
      await page.evaluate(()=>Object.defineProperty(navigator,'userAgent',{configurable:true,get:()=> 'Synthetic MicroMessenger'}));
      await checkoutSubmit.click();await page.waitForFunction(()=>document.body.innerText.includes('缴费失败'));
      assert.equal(submissions.length,1);assert.deepEqual(submissions[0].oweIds,['owe-b']);assert.equal(submissions[0].receivedAmount,'0.01');
      await page.goto(base+'/#/pages/roomOweFee/roomOweFee?roomId=synthetic-room');
      await page.locator('.selection-summary').getByText('已选 0 项',{exact:true}).waitFor();
      await page.goto(base+'/#/pages/roomOweFee/roomOweFee?roomId=synthetic-room&oweIds='+encodeURIComponent(JSON.stringify(['owe-b','missing'])));
      await page.locator('.selection-error').waitFor();await page.locator('.selection-summary').getByText('已选 0 项',{exact:true}).waitFor();
      assert.deepEqual(errors,[]);checks.push({width,passed:true,submittedIds:['owe-b'],providerCalled:false});await context.close();
    }
    fs.writeFileSync(path.join(folder,'browser.json'),JSON.stringify({passed:true,mockedApi:true,checks,payments:0,messagesSent:0},null,2));
    console.log('Selective compiled H5 passed at360/390/430px: defaults, checkbox totals, navigation, subset submission and stale selection. No provider calls.');
  } catch(error) {
    if(lastPage){await lastPage.screenshot({path:path.join(folder,'failure.png'),fullPage:true});fs.writeFileSync(path.join(folder,'failure.txt'),await lastPage.locator('body').innerText());}
    throw error;
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
