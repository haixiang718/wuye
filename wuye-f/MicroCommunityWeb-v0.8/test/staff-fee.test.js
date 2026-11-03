'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const vm=require('node:vm');
function screen(response={total:60,staffFees:[]}) {
  const requests=[],events=[],messages=[];
  const vc={getCurrentCommunity:()=>({communityId:'community'}),
    extends(options){this.component=Object.assign({},options.data,options.methods);},
    http:{get(component,method,params,success){requests.push(JSON.parse(JSON.stringify(params.params)));success(JSON.stringify(response));}},
    emit(...args){events.push(args);},toast(msg){messages.push(msg);}};
  const context={window:{vc},console};
  vm.runInNewContext(fs.readFileSync(require.resolve('../public/pages/property/staffFeeManage/staffFeeManage.js'),'utf8'),context);
  return {view:vc.component,requests,events,messages};
}
test('staff fee pagination counts detail rows and handles exact page multiples',()=>{
  const app=screen();app.view._liststaffFees(2,30);
  assert.equal(app.view.staffFeeManageInfo.records,2);
  assert.equal(app.events[0][2].currentPage,2);
});
test('empty staff fee responses clear rows and total pages',()=>{
  const app=screen({total:0});app.view._liststaffFees(1,30);
  assert.equal(app.view.staffFeeManageInfo.staffFees.length,0);
  assert.equal(app.view.staffFeeManageInfo.records,0);
});
test('separate records keep their charge timestamps and amounts',()=>{
  const rows=[{detailId:'a',createTime:'2026-11-03 10:00:00',receivedAmount:10},
    {detailId:'b',createTime:'2026-11-03 11:00:00',receivedAmount:20}];
  const app=screen({total:2,staffFees:rows});app.view._liststaffFees(1,30);
  assert.deepEqual(JSON.parse(JSON.stringify(app.view.staffFeeManageInfo.staffFees)),rows);
});
test('invalid date ranges show a message without requesting records',()=>{
  const app=screen();app.view.staffFeeManageInfo.conditions.startTime='2026-11-04';
  app.view.staffFeeManageInfo.conditions.endTime='2026-11-03';
  app.view._querystaffFeeMethod();
  assert.equal(app.requests.length,0);assert.equal(app.messages.length,1);
});
test('CSV export preserves separate payment identifiers, times and escaping',()=>{
  const app=screen();app.view.staffFeeManageInfo.staffFees=[
    {detailId:'detail-a',createTime:'2026-11-03 10:00:00',userName:'=1+1',userId:'12345678901234567890',feeName:'水费,"电费"',receivableAmount:10,receivedAmount:10},
    {detailId:'detail-b',createTime:'2026-11-03 11:00:00',userName:'collector',receivedAmount:20}];
  const csv=app.view._staffFeeCsv();
  assert(csv.includes('2026-11-03 10:00:00'));assert(csv.includes('2026-11-03 11:00:00'));
  assert(csv.includes('detail-a'));assert(csv.includes('detail-b'));
  assert(csv.includes('水费,""电费""'));assert(!csv.includes('"=1+1"'));
});
