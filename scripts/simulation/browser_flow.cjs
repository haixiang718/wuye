'use strict';
// Actual local sessions and compiled H5; no real OAuth, notifications, or payments.
const fs=require('node:fs');
const path=require('node:path');
const {spawn}=require('node:child_process');
const {chromium,request}=require('../../.tools/browser/node_modules/playwright-core');
const {createGateway}=require('../wechat-test-gateway.cjs');
const root=path.resolve(__dirname,'../..');
const folder=path.join(root,'.runtime/billing-flow-simulation');
const read=name=>JSON.parse(fs.readFileSync(path.join(folder,name),'utf8'));
const fixture=read('fixture.json');
if(!/^SIM[a-f0-9]{12}$/.test(fixture.prefix)||fixture.label!=='LOCAL FULL FLOW SIMULATION ONLY - DO NOT PAY')throw new Error('Invalid simulation fixture');
const mode=process.argv[2];
if(!['approve','publish','before-bill','after-bill','after-publish','after-notice'].includes(mode))throw new Error('Unknown simulation mode');
const checks=[];
let completed=false,failure=null;
const report=()=>fs.writeFileSync(path.join(folder,'browser-'+mode+'.json'),JSON.stringify({mode,completed,failure,checks,passed:completed&&checks.length>0&&checks.every(item=>item.passed)},null,2));
const check=(name,passed,details={})=>{checks.push({name,passed,...details});report();};
const headers={'app-id':'992020061440050003','transaction-id':fixture.prefix,'req-time':'20261102000000','sign':'1234567','user-id':'-1'};
let browser,ownerClient,anonymous,server,gateway;
(async()=>{
  try {
    browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
    ownerClient=await request.newContext({timeout:30000});
    anonymous=await request.newContext({timeout:30000});
    if(mode==='approve') {
      const pending=await ownerClient.post('http://127.0.0.1:8012/app/loginOwner',{headers,data:{username:fixture.ownerName,password:fixture.password,loginByPhone:false}});
      check('pending_binding_cannot_login',pending.status()===403,{http:pending.status()});
      const context=await browser.newContext();
      await context.addCookies(JSON.parse(fs.readFileSync(path.join(root,'.runtime/browser-cookies.json'),'utf8').replace(/^\uFEFF/,'')));
      const page=await context.newPage();
      await page.goto('http://127.0.0.1:3000/admin.html',{waitUntil:'domcontentloaded'});
      await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity());
      const approval=await page.evaluate(data=>new Promise(resolve=>{
        const timer=setTimeout(()=>resolve({code:null,timeout:true}),30000);
        vc.http.post('auditAppUserBindingOwnerManage','audit',JSON.stringify(data),{emulateJSON:true},body=>{
          clearTimeout(timer);try{const result=typeof body==='string'?JSON.parse(body):body;resolve({code:Number(result.code)});}catch{resolve({code:null});}
        },()=>{clearTimeout(timer);resolve({code:null});});
      }),{appUserId:fixture.appUserId,communityId:fixture.communityId,state:'1100',remark:'Local simulation approval'});
      check('property_approval_via_real_management_entry',approval.code===0,approval);
      const response=await ownerClient.post('http://127.0.0.1:8012/app/loginOwner',{headers,data:{username:fixture.ownerName,password:fixture.password,loginByPhone:false}});
      let auth;try{auth=await response.json();}catch{}
      check('approved_owner_password_login',response.status()===200&&!!auth?.token&&auth?.owner?.ownerId===fixture.ids.owner,{http:response.status()});
      if(auth?.token)fs.writeFileSync(path.join(folder,'auth.json'),JSON.stringify(auth));
    } else if(mode==='publish') {
      const context=await browser.newContext();
      await context.addCookies(JSON.parse(fs.readFileSync(path.join(root,'.runtime/browser-cookies.json'),'utf8').replace(/^\uFEFF/,'')));
      const page=await context.newPage();page.on('dialog',dialog=>dialog.accept());
      await page.goto('http://127.0.0.1:3000/admin.html',{waitUntil:'domcontentloaded'});
      await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity());
      await page.evaluate(()=>window.location.hash='#/pages/property/billManage');
      const row=page.locator('tbody tr').filter({hasText:fixture.billId});
      await row.getByText('审核发布',{exact:true}).waitFor({timeout:30000});
      check('management_draft_bill_has_review_button',true);
      await row.getByText('审核发布',{exact:true}).click();
      await row.getByText('已审核发布',{exact:true}).waitFor({timeout:30000});
      check('management_review_button_publishes_bill',true);
      await page.screenshot({path:path.join(folder,'management-published.png'),fullPage:true});
    } else {
      const auth=read('auth.json');
      const scoped={...headers,'app-id':'992020061452450002',cookie:'_java110_token_='+auth.token};
      const query={communityId:fixture.communityId,payerObjId:fixture.ids.room,state:'2008001',page:1,row:30};
      const feeResponse=await ownerClient.get('http://127.0.0.1:8012/app/fee.listFee',{headers:scoped,params:query});
      let fees;try{fees=await feeResponse.json();}catch{}
      const found=Array.isArray(fees?.fees)&&fees.fees.some(item=>item.feeId===fixture.ids.fee);
      const draft=mode==='before-bill'||mode==='after-bill';
      check(draft?'unpublished_fee_is_hidden':'owner_fee_visible_'+mode,feeResponse.status()===200&&(draft?!found:found),{http:feeResponse.status(),matchingFixtureFee:found});
      if(!draft&&found) {
        const bill=fees.fees.find(item=>item.feeId===fixture.ids.fee);
        check('owner_receives_published_snapshot_amount',bill.billSnapshot===true&&bill.oweId===fixture.oweId&&Number(bill.feePrice)===0.01);
      }
      if(mode==='after-publish') {
        for(const endpoint of ['toPay','toOweFeePay','toQrOweFeePay']) {
          const disabled=await ownerClient.post('http://127.0.0.1:8012/app/payment/'+endpoint,{headers:scoped,
            data:{communityId:fixture.communityId,feeId:fixture.ids.fee,roomId:fixture.ids.room}});
          check('server_blocks_unconfigured_payment_'+endpoint,disabled.status()===503,{http:disabled.status()});
        }
      }
      if(mode!=='before-bill') {
        const response=await ownerClient.get('http://127.0.0.1:8012/app/feeApi/listOweFees',{headers:scoped,params:{communityId:fixture.communityId,payObjId:fixture.ids.room,payObjType:'3333',page:1,row:50}});
        let body;try{body=await response.json();}catch{}
        const present=Array.isArray(body?.data)&&body.data.some(item=>item.feeId===fixture.ids.fee);
        check(draft?'draft_bill_hidden_from_arrears':'published_unpaid_bill_remains_in_arrears_list',response.status()===200&&(draft?!present:present),{http:response.status(),matchingUnpaidBill:present});
      }
      const unauth=await anonymous.get('http://127.0.0.1:8012/app/fee.listFee',{headers:{...headers,'app-id':'992020061452450002','X-Requested-With':'XMLHttpRequest'},params:query,maxRedirects:0});
      check('anonymous_fee_access_denied',[301,302,401,403].includes(unauth.status()),{http:unauth.status()});
      const other=JSON.parse(fs.readFileSync(path.join(root,'.runtime/wechat-test-fixture.json'),'utf8'));
      const cross=await ownerClient.get('http://127.0.0.1:8012/app/fee.listFee',{headers:{...scoped,'user-id':other.createdUserId||'-1'},params:{...query,payerObjId:other.ids.room},maxRedirects:0});
      check('other_owner_room_denied_despite_spoofed_header',cross.status()===403,{http:cross.status()});
      server=spawn(process.execPath,['scripts/owner-h5.cjs','serve'],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
      await new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>reject(new Error('Preview startup timeout')),10000);
        server.stdout.once('data',()=>{clearTimeout(timer);resolve();});
        server.once('error',error=>{clearTimeout(timer);reject(error);});
        server.once('exit',()=>{clearTimeout(timer);reject(new Error('Preview exited'));});
      });
      const config={accessCode:'simulation-local-only',sessionSecret:require('node:crypto').randomBytes(32).toString('hex'),appId:'simulation-public-account',communityId:fixture.communityId,ownerH5Port:8000};
      gateway=createGateway({config:()=>config,owner:'http://127.0.0.1:8000',backend:'http://127.0.0.1:8012'});
      await new Promise(resolve=>gateway.listen(0,'127.0.0.1',resolve));
      const base='http://127.0.0.1:'+gateway.address().port;
      const context=await browser.newContext({viewport:{width:390,height:844}});
      await context.request.post(base+'/wechat-test/enter',{form:{code:config.accessCode}});
      const page=await context.newPage();const errors=[];
      page.on('pageerror',error=>errors.push(error.message));
      // Stop any unexpected external navigation; no real WeChat OAuth occurs.
      await page.route('**/*',route=>new URL(route.request().url()).hostname==='127.0.0.1'?route.continue():route.abort());
      await page.goto(base+'/#/pages/index/index');
      await page.getByText('物业费',{exact:true}).waitFor();
      await page.evaluate(auth=>{
        uni.setStorageSync('token',auth.token);uni.setStorageSync('ownerInfo',auth.owner);
        uni.setStorageSync('userInfo',JSON.stringify(auth.owner));
        uni.setStorageSync('currentCommunityInfo',{communityId:auth.owner.communityId,communityName:auth.owner.communityName});
        uni.setStorageSync('loginFlag',{sessionKey:auth.owner.userId,expireTime:Date.now()+3600000});
      },auth);
      await page.goto(base+'/#/pages/my/myHouse');
      await page.getByText('SIM101室',{exact:false}).waitFor({timeout:15000});
      check('compiled_h5_shows_own_room',true);
      await page.goto(base+'/#/pages/roomFeeListNew/roomFeeListNew?roomId='+encodeURIComponent(fixture.ids.room));
      if(draft) {
        await page.waitForLoadState('networkidle');
        check('compiled_h5_hides_unpublished_fee',await page.getByText('SIMULATION monthly fee - DO NOT PAY',{exact:false}).count()===0);
      } else {
        await page.getByText('SIMULATION monthly fee - DO NOT PAY',{exact:false}).waitFor({timeout:15000});
        check('compiled_h5_shows_fixture_fee',true);
        await page.getByText('SIMULATION monthly fee - DO NOT PAY',{exact:false}).click();
        const disabled=page.getByText('微信支付未开通',{exact:true});
        await disabled.waitFor({timeout:15000});
        check('payment_button_stays_disabled',await disabled.getAttribute('disabled')!==null);
        await page.getByText('0.01元',{exact:false}).first().waitFor({timeout:15000});
        check('compiled_detail_reloads_frozen_bill_amount',true);
      }
      check('compiled_h5_has_no_page_errors',errors.length===0,{errorCount:errors.length});
      await page.screenshot({path:path.join(folder,'h5-'+mode+'.png'),fullPage:true});
      if(mode==='after-notice') {
        const previous=errors.length;
        await page.goto(base+'/#/pages/roomOweFee/roomOweFee?roomId='+encodeURIComponent(fixture.ids.room));
        await page.waitForLoadState('networkidle');
        await page.getByText('SIMULATION monthly fee - DO NOT PAY',{exact:false}).waitFor({timeout:15000});
        await page.getByText('合计：0.01元',{exact:false}).waitFor({timeout:15000});
        check('h5_arrears_page_displays_snapshot_and_total',true);
        check('h5_arrears_page_loads_without_runtime_errors',errors.length===previous,{newErrorCount:errors.length-previous,undefinedRoomLoader:errors.some(message=>message.includes('_loadRoomFee'))});
      }
    }
    completed=true;
    if(checks.some(item=>!item.passed))process.exitCode=1;
    console.log(JSON.stringify({mode,checks:checks.length,failed:checks.filter(item=>!item.passed).map(item=>item.name)}));
  } finally {
    report();
    if(browser)await browser.close();
    if(ownerClient)await ownerClient.dispose();if(anonymous)await anonymous.dispose();
    if(gateway)await new Promise(resolve=>gateway.close(resolve));if(server)server.kill();
  }
})().catch(error=>{
  fs.writeFileSync(path.join(folder,'browser-'+mode+'-error-private.log'),error.stack||String(error));
  completed=false;failure={errorType:error.name};
  check('simulation_step_completed',false,failure);
  console.error('Simulation browser step failed: '+error.name+'; inspect private runtime state.');process.exitCode=2;
});
