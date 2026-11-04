'use strict';
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('../../.tools/browser/node_modules/playwright-core');
const root=path.resolve(__dirname,'../..'),folder=path.join(root,'.runtime/owner-room-creation-flow');
const f=JSON.parse(fs.readFileSync(path.join(folder,'fixture.json'),'utf8'));
if(f.label!=='LOCAL OWNER ROOM CREATION ONLY - DO NOT PAY'||!/^ORC[a-f0-9]{12}$/.test(f.prefix))throw Error('Fixture provenance mismatch');
const report={checks:[],messagesSent:0,payments:0,pageErrors:[]},base='https://admin.jiaanfuwu.cn';
function check(name,passed){report.checks.push({name,passed:!!passed});fs.writeFileSync(path.join(folder,'browser.json'),JSON.stringify(report,null,2));if(!passed)throw Error(name);}
async function post(page,payload){
  return page.evaluate(payload=>new Promise(resolve=>{
    vc.http.post('addOwner','saveOwner',JSON.stringify(payload),{emulateJSON:true},
      (text,res)=>{try{resolve({http:res.status,...JSON.parse(text)});}catch{resolve({invalidResponse:true});}},
      (text,error)=>resolve({failed:true,http:error&&error.status}));
  }),payload);
}
(async()=>{
  const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
  let page;
  try{
    const context=await browser.newContext({viewport:{width:1440,height:1100}});
    const cookies=JSON.parse(fs.readFileSync(path.join(root,'.runtime/browser-cookies.json'),'utf8').replace(/^\uFEFF/,''));
    await context.addCookies(cookies.map(c=>({...c,domain:'admin.jiaanfuwu.cn',secure:true})));
    page=await context.newPage();page.setDefaultTimeout(45000);
    page.on('pageerror',e=>report.pageErrors.push(e.message));
    await page.goto(base+'/admin.html',{waitUntil:'networkidle',timeout:90000});
    await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity());
    await page.goto(base+'/admin.html#/pages/property/listOwner',{waitUntil:'networkidle',timeout:90000});
    await page.waitForFunction(()=>window.vc&&vc.getCurrentCommunity()&&vc.component.listOwnerInfo);
    check('expected_current_community',await page.evaluate(()=>vc.getCurrentCommunity().communityId)===f.communityId);
    await page.getByRole('button',{name:/添加业主/}).click();
    const modal=page.locator('#addOwnerModel');await modal.waitFor({state:'visible'});
    await modal.locator('input[placeholder="必填，请填写姓名"]').fill(f.name);
    await modal.locator('select').first().selectOption('0');
    await modal.locator('input[placeholder="可选，请填写年龄"]').fill('35');
    await modal.locator('input[placeholder="可选，请填写身份证"]').fill(f.idCard);
    await modal.locator('input[placeholder="必填，请填写联系方式"]').fill(f.phone);
    await modal.locator('input[placeholder="可填，请填写备注"]').fill(f.label);
    await modal.locator('#addOwnerRoomFloor').fill(f.floorNum);
    await modal.getByRole('button',{name:'查询可绑定房屋',exact:true}).click();
    await page.waitForFunction(()=>!vc.component.addOwnerInfo.roomLoading&&vc.component.addOwnerInfo.roomOptions.length===3);
    check('picker_queries_only_fixture_floor',await modal.locator('tbody tr').count()===3);
    check('picker_shows_full_floor_numbers',await page.evaluate(floor=>vc.component.addOwnerInfo.roomOptions.every(r=>r.floorNum===floor),f.floorNum));
    check('picker_uses_actual_total_and_page_counts',await page.evaluate(()=>vc.component.addOwnerInfo.roomRecords===3&&vc.component.addOwnerInfo.roomPages===1));
    for(const number of ['T101','T102'])await modal.locator('tbody tr').filter({hasText:number}).getByRole('checkbox').check();
    await modal.getByText('已选2套',{exact:true}).waitFor();
    check('two_rooms_selected_in_same_modal',true);
    await page.screenshot({path:path.join(folder,'two-rooms-selected.png'),fullPage:true});
    const payload={name:f.name,age:'35',sex:'0',link:f.phone,idCard:f.idCard,ownerId:-1,ownerTypeCd:'-1',communityId:f.communityId,remark:f.label,attrs:[]};
    const duplicate=await post(page,{...payload,roomIds:[f.ids.room,f.ids.room]});
    check('duplicate_room_ids_rejected',duplicate.failed||duplicate.code!==0);
    const missing=await post(page,{...payload,roomIds:[f.ids.room,'nonexistent-'+f.prefix]});
    check('missing_room_rejects_whole_request',missing.failed||missing.code!==0);
    // The real owner-attribute staging table accepts at most 50 characters.
    // Force a late order-staging failure after owner/room business assembly,
    // then prove the same identity/rooms can still be saved normally.
    const staging=await post(page,{...payload,roomIds:[f.ids.room,f.ids.room2],
      attrs:[{specCd:'081606740011',value:'synthetic-'.repeat(8)}]});
    check('late_order_staging_failure_reported',staging.failed||staging.code!==0);
    const success=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/addOwner/saveOwner'));
    await modal.getByRole('button',{name:/保存/}).click();
    const reply=await (await success).json();
    report.saveCode=reply.code;
    check('one_ui_save_creates_owner_and_rooms',reply.code===0);
    await modal.waitFor({state:'hidden'});
    const retry=await post(page,{...payload,roomIds:[f.ids.room,f.ids.room2]});
    check('repeat_request_cannot_duplicate_owner',retry.failed||retry.code!==0);
    const otherCard=f.idCard.slice(0,14)+(f.idCard.slice(14,17)==='998'?'997':'998')+'0';
    const stale=await post(page,{...payload,name:f.name+'乙',idCard:otherCard,roomIds:[f.ids.room,f.ids.spare]});
    check('bound_plus_free_selection_rejects_whole_request',stale.failed||stale.code!==0);
    const found=await page.evaluate(name=>new Promise((resolve,reject)=>{
      vc.http.get('listOwner','list',{params:{communityId:vc.getCurrentCommunity().communityId,ownerTypeCd:'1001',page:1,row:10,name}},
        raw=>{try{const r=JSON.parse(raw);resolve((r.owners||[]).filter(o=>o.name===name).map(o=>({ownerId:o.ownerId,memberId:o.memberId})));}catch{reject(Error('Owner lookup malformed'));}},
        ()=>reject(Error('Owner lookup failed')));
    }),f.name);
    check('saved_owner_found_once',found.length===1&&found[0].ownerId===found[0].memberId);
    report.ownerId=found[0].ownerId;
    check('no_page_errors',report.pageErrors.length===0);
    report.passed=true;
    console.log(JSON.stringify({passed:true,checks:report.checks.length,messagesSent:0,payments:0}));
  }catch(error){report.failure=error.message;if(page){report.finalUrl=page.url();await page.screenshot({path:path.join(folder,'failure.png'),fullPage:true});}console.error('Owner-room browser verification failed; inspect private report.');process.exitCode=1;}
  finally{fs.writeFileSync(path.join(folder,'browser.json'),JSON.stringify(report,null,2));await browser.close();}
})();
