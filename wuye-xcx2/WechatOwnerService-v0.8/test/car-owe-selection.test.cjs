const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function page(){
  const requests=[],toasts=[];
  const context={constant:{url:{toOweFeePay:'/owe'}},getHeaders:()=>({}),request:req=>requests.push(req)};
  const script=fs.readFileSync(path.join(__dirname,'../pages/carOweFee/carOweFee.vue'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g,'').replace('export default','module.exports=');
  const sandbox={module:{exports:{}},require:name=>name.includes('WexinPayFactory')?{}:context,
    billSelection:require('../utils/bill-selection'),uni:{showToast:data=>toasts.push(data)},wx:{showLoading(){}}};
  vm.runInNewContext(script,sandbox);const def=sandbox.module.exports;
  return {c:{...def.data(),...def.methods},requests,toasts};
}
test('parking payment explicitly includes every displayed published bill for both callers',()=>{
  for(const method of ['onPayFee','_payWxApp']){
    const p=page();p.c.fees=[{oweId:'owe-car',feeId:'fee-car',billId:'bill-car',billSnapshot:true,feePrice:'0.01'}];
    p.c.receivableAmount='0.01';p.c[method]();assert.equal(p.requests.length,1);
    assert.deepEqual(Array.from(p.requests[0].data.oweIds),['owe-car']);assert.equal(p.requests[0].data.payObjType,'6666');
  }
});
test('parking never posts an empty or incomplete bill selection',()=>{
  for(const fees of [[],[{feePrice:'1.00'}],[{oweId:'a',feeId:'f',billId:'b',billSnapshot:true,feePrice:'1.00'},{feePrice:'bad'}]]){
    const p=page();p.c.fees=fees;p.c.onPayFee();assert.equal(p.requests.length,0);assert.equal(p.toasts.length,1);
  }
});
