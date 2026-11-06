const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const read=file=>fs.readFileSync(path.join(__dirname,'..',file),'utf8');
function history(){
  const requests=[],events={};let ready,owner={communityId:'c',memberId:'m'};
  const context={constant:{url:{queryFeeDetail:'/app/fee.queryFeeDetail'}},onLoad:(opts,cb)=>{ready=cb;},getHeaders:()=>({}),request:opts=>requests.push(opts)};
  const script=read('pages/payFeeDetail/payFeeDetail.vue').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g,'').replace(/\/\/ #ifndef H5[\s\S]*?\/\/ #endif/g,'').replace('export default','module.exports=');
  const sandbox={module:{exports:{}},noDataPage:{},require:()=>context,getCurOwner:async()=>owner,
    feeDisplay:require('../utils/fee-display'),uni:{$on:(name,cb)=>events[name]=cb,$off:()=>{},stopPullDownRefresh:()=>{}}};
  vm.runInNewContext(script,sandbox);const definition=sandbox.module.exports,component={...definition.data(),...definition.methods};
  return {component,requests,load:opts=>definition.onLoad.call(component,opts||{}),ready:()=>ready(),
    show:()=>definition.onShow.call(component),hide:()=>definition.onHide.call(component),
    setOwner:value=>{owner=value;},change:()=>events['owner-wechat-session-changed'](),next:()=>new Promise(setImmediate)};
}
test('history waits for authorization then queries community without client owner filter',async()=>{
  const h=history();h.load();h.show();assert.equal(h.requests.length,0);h.ready();await h.next();
  assert.equal(h.requests.length,1);assert.equal(h.requests[0].data.communityId,'c');assert.equal(h.requests[0].data.ownerId,undefined);
  h.requests[0].success({statusCode:200,data:{code:0,total:1,feeDetails:[{detailId:'paid',receivedAmount:'0.01',createTime:'2026-11-06 10:00:00',startTime:null}]}});
  await h.next();assert.equal(h.component.feeDetails[0].receivedAmount,'0.01');assert.equal(h.component.noData,false);assert.equal(h.component.error,'');
});
test('failed history stays an error and can retry after an empty response',async()=>{
  const h=history();h.load();h.ready();await h.next();h.requests[0].success({statusCode:403,data:{msg:'denied'}});await h.next();
  assert.ok(h.component.error);assert.equal(h.component.noData,false);h.component.reload();await h.next();
  h.requests[1].success({statusCode:200,data:{code:0,total:0,feeDetails:[]}});await h.next();assert.equal(h.component.noData,true);
  h.component.reload();await h.next();h.requests[2].success({statusCode:200,data:{code:0,total:1,feeDetails:[{detailId:'paid'}]}});await h.next();
  assert.equal(h.component.noData,false);assert.equal(h.component.feeDetails.length,1);
});
test('pagination appends, ignores hidden-page responses and clears data on session change',async()=>{
  const h=history();h.load();h.ready();await h.next();h.requests[0].success({statusCode:200,data:{code:0,total:21,feeDetails:[{detailId:'first'}]}});await h.next();
  h.component.loadMore();await h.next();assert.equal(h.requests[1].data.page,2);
  h.requests[1].success({statusCode:200,data:{code:0,total:21,feeDetails:[{detailId:'second'}]}});await h.next();assert.equal(h.component.feeDetails.length,2);
  h.component.reload();await h.next();h.hide();h.requests[2].success({statusCode:200,data:{code:0,total:1,feeDetails:[{detailId:'stale'}]}});await h.next();assert.equal(h.component.feeDetails.length,0);
  h.show();await h.next();h.setOwner({communityId:'other'});h.change();await h.next();assert.equal(h.component.feeDetails.length,0);
  assert.equal(h.requests.at(-1).data.communityId,'other');
});
test('bill amounts are present on list cards and missing values never become zero',()=>{
  const display=require('../utils/fee-display');
  assert.equal(display.money('0.01'),'0.01');assert.equal(display.money(1234.5),'1234.50');assert.equal(display.money(null),'');assert.equal(display.money(''),'');assert.equal(display.money('bad'),'');
  assert.equal(display.date(null),'—');assert.equal(display.date('2026-11-06 10:00:00'),'2026-11-06');
  assert.match(read('pages/roomFeeListNew/roomFeeListNew.vue').split('</template>')[0],/待缴/);
  assert.match(read('pages/roomFeeListNew/roomFeeListNew.vue').split('</template>')[0],/fee\.amount/);
});
