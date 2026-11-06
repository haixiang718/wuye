'use strict';
// Compiled H5 with synthetic browser identity and mocked read-only API responses.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('../../.tools/browser/node_modules/playwright-core');
const root=path.resolve(__dirname,'../..'),folder=path.join(root,'.runtime/owner-h5-cleanup-check');
fs.mkdirSync(folder,{recursive:true});
const owner={ownerId:'synthetic-owner',memberId:'synthetic-owner',userId:'synthetic-user',communityId:'synthetic-community',communityName:'界面测试小区',state:'12000',appUserName:'界面测试业主'};
const checks=[];let lastPage;
(async()=>{
  const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
  try {
    for(const width of [360,390,430]) {
      const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage(),errors=[];
      lastPage=page;let historyMode='normal';
      page.on('pageerror',error=>errors.push(error.message));
      await page.route('**/wechat-test/config.js',route=>route.fulfill({contentType:'application/javascript',body:'window.__WUYE_WECHAT_TEST__={appId:"synthetic-app",communityId:"synthetic-community",paymentEnabled:false};'}));
      await page.route('**/app/**',route=>{
        const url=route.request().url();let body={code:0,data:[]};
        if(url.includes('queryRoomsByOwner'))body={code:0,rooms:[{roomId:'synthetic-room',floorNum:'3',unitNum:'2',roomNum:'101'}]};
        else if(url.includes('fee.listFee'))body={code:0,total:2,fees:[
          {feeId:'synthetic-fee',oweId:'synthetic-owe',billId:'synthetic-bill',feeName:'物业服务费（2026年11月）',feePrice:'1234.50',billSnapshot:true,endTime:'2026-11-01 00:00:00',feeEndTime:'2026-11-01 00:00:00',deadlineTime:'2026-12-01 00:00:00'},
          {feeId:'synthetic-fee2',feeName:'超长收费项目名称用于验证手机排版不会遮挡金额或超出屏幕',feePrice:'0.01',billSnapshot:true,endTime:'2026-11-01',feeEndTime:'2026-11-01',deadlineTime:'2026-12-01'}]};
        else if(url.includes('fee.queryFeeDetail'))body=historyMode==='error'?{code:1,msg:'Synthetic error'}:historyMode==='empty'?{code:0,total:0,feeDetails:[]}:{code:0,total:1,feeDetails:[{detailId:'synthetic-paid',feeName:'物业服务费',receivedAmount:'0.01',payerObjName:'3-2-101',createTime:'2026-11-06 10:00:00',startTime:'2026-11-01',endTime:'2026-12-01'}]};
        else if(url.includes('listAppUserBindingOwners'))body={code:0,data:[owner]};
        return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
      });
      await page.goto('http://127.0.0.1:8000/#/pages/index/index');
      await page.getByText('物业费',{exact:true}).waitFor();
      await page.evaluate(owner=>{
        uni.setStorageSync('owner_wechat_identity',{wechatSession:'synthetic-only',hasBinding:true,expiresAt:Date.now()+3600000});
        uni.setStorageSync('owner_verified_bindings',[owner]);uni.setStorageSync('ownerInfo',owner);uni.setStorageSync('userInfo',JSON.stringify(owner));
        uni.setStorageSync('loginFlag',{sessionKey:owner.userId,expireTime:Date.now()+3600000});uni.setStorageSync('token','synthetic-layout-only');
        uni.setStorageSync('currentCommunityInfo',{communityId:owner.communityId,communityName:owner.communityName});uni.$emit('owner-wechat-session-changed');
      },owner);
      assert.equal(await page.locator('.home-service').count(),3);
      for(const name of ['家庭成员','房屋出租','联系客服','车位申请','空置房','房屋装修','合同费用','投票问卷'])assert.equal(await page.getByText(name,{exact:true}).count(),0,name);
      await page.screenshot({path:path.join(folder,'home-'+width+'.png'),fullPage:true});
      await page.goto('http://127.0.0.1:8000/#/pages/my/my');await page.getByText('缴费历史',{exact:true}).waitFor();
      assert.equal(await page.getByText('房屋装修',{exact:true}).count(),0);
      await page.goto('http://127.0.0.1:8000/#/pages/roomFeeListNew/roomFeeListNew');
      await page.getByText('¥1234.50',{exact:true}).waitFor();await page.getByText('¥0.01',{exact:true}).waitFor();
      assert.ok(await page.getByText(/账期 2026-11-01 至 2026-12-01/).count());
      const amountVisible=await page.locator('.bill-amount').evaluateAll(nodes=>nodes.every(node=>{const r=node.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth;}));assert.ok(amountVisible);
      await page.screenshot({path:path.join(folder,'bills-'+width+'.png'),fullPage:true});
      await page.goto('http://127.0.0.1:8000/#/pages/payFeeDetail/payFeeDetail');
      await page.getByText('¥0.01',{exact:true}).waitFor();await page.getByText('2026-11-06 10:00:00',{exact:true}).waitFor();
      await page.getByText('3-2-101',{exact:true}).waitFor();
      assert.equal(await page.locator('.history-card').count(),1);
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
      await page.screenshot({path:path.join(folder,'history-'+width+'.png'),fullPage:true});
      if(width===390){
        historyMode='error';await page.reload();await page.getByText('重新加载',{exact:true}).waitFor();
        assert.equal(await page.getByText('暂无缴费记录',{exact:true}).count(),0);
        historyMode='empty';await page.getByText('重新加载',{exact:true}).click();await page.getByText('暂无缴费记录',{exact:true}).waitFor();
      }
      assert.deepEqual(errors,[]);checks.push({width,passed:true});await context.close();
    }
    fs.writeFileSync(path.join(folder,'browser.json'),JSON.stringify({passed:true,checks,mockedApi:true,payments:0,messagesSent:0},null,2));
    console.log('Compiled H5: home cleanup, account menu, bill amounts, history, empty/error retry; 360/390/430px passed.');
  } catch(error) {
    if(lastPage){await lastPage.screenshot({path:path.join(folder,'failure.png'),fullPage:true});fs.writeFileSync(path.join(folder,'failure.txt'),await lastPage.locator('body').innerText());}
    throw error;
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
