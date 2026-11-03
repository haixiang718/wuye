const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
function page(getRooms) {
  const source=fs.readFileSync(path.join(__dirname,'../pages/my/myHouse.vue'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g,'').replace('export default','module.exports =');
  const context={constant:{},getRooms},messages=[];
  const sandbox={module:{exports:{}},require:()=>context,getRooms,uni:{showToast:options=>messages.push(options.title),stopPullDownRefresh(){}}};
  vm.runInNewContext(source,sandbox);
  const definition=sandbox.module.exports;
  return {component:{...definition.data(),...definition.methods},messages};
}
test('house request failure clears previous rooms and shows an explicit retry state',async()=>{
  const p=page(async()=>{throw new Error('offline');});p.component.rooms=[{roomId:'stale'}];
  await assert.doesNotReject(async()=>{await p.component.loadOwnerHouse();await new Promise(setImmediate);});
  assert.equal(p.component.rooms.length,0);assert.match(p.component.loadError,/失败|重试/);
});
test('latest house lookup wins over an older delayed response',async()=>{
  const pending=[];const p=page(()=>new Promise(resolve=>pending.push(resolve)));
  const first=p.component.loadOwnerHouse();const latest=p.component.loadOwnerHouse();
  pending[1]({rooms:[{roomId:'current'}]});await latest;
  pending[0]({rooms:[{roomId:'stale'}]});await first;
  assert.equal(p.component.rooms[0].roomId,'current');
  assert.equal(p.component.loadError,'');
});
test('empty house results keep a resolved empty list rather than a loading error',async()=>{
  const p=page(async()=>({rooms:[]}));await p.component.loadOwnerHouse();
  assert.equal(p.component.rooms.length,0);assert.equal(p.component.loadError,'');
});
