'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function screen() {
  let definition,community='community',allowed=true,confirmed=true;
  const posts=[],events=[],toasts=[],prompts=[],refreshes=[],handlers={};
  const vc={extends(d){definition=d;this.component={...d.data,...d.methods};},
    getCurrentCommunity:()=>({communityId:community}),hasPrivilege:()=>allowed,
    emit:(...args)=>events.push(args),toast:x=>toasts.push(x),on:(c,e,h)=>handlers[e]=h,
    http:{post:(...args)=>posts.push(args)}};
  const context={window:{vc,confirm:x=>(prompts.push(x),confirmed)},console:{log(){}}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../public/pages/property/room/room.js'),'utf8'),context);
  context.$that=vc.component;definition._initEvent();vc.component.listRoom=(...a)=>refreshes.push(a);
  return {c:vc.component,posts,events,toasts,prompts,refreshes,handlers,
    community:id=>community=id,permission:v=>allowed=v,confirm:v=>confirmed=v};
}
const room=()=>({roomId:'room',floorId:'floor',floorNum:'1',unitNum:'2',roomNum:'301',state:'2002'});
test('room binding selects an existing owner for the already chosen room',()=>{
  const s=screen();s.c._openBindRoomOwner(room());assert.equal(s.events[0][0],'searchOwner');
  s.handlers.chooseOwner({ownerId:'owner',name:'测试业主',communityId:'community'});
  assert.equal(s.posts.length,1);assert.ok(s.prompts[0].includes('1-2-301'));assert.ok(s.prompts[0].includes('测试业主'));
  assert.equal(s.posts[0][0],'addOwnerRoomBinding');assert.equal(s.posts[0][1],'binding');
  assert.deepEqual(JSON.parse(s.posts[0][2]),{communityId:'community',data:[
    {flowComponent:'viewFloorInfo',floorId:'floor'},
    {flowComponent:'sellRoomSelectRoom',roomId:'room'},
    {flowComponent:'viewOwnerInfo',ownerId:'owner'}]});
});
test('binding success refreshes the room page and prevents repeat submissions',()=>{
  const s=screen();s.c.roomInfo.currentPage=3;s.c._openBindRoomOwner(room());
  s.handlers.chooseOwner({ownerId:'owner',name:'测试业主'});s.handlers.chooseOwner({ownerId:'owner'});assert.equal(s.posts.length,1);
  s.posts[0][4](JSON.stringify({code:0}),{status:200});assert.deepEqual(s.refreshes,[[3,10]]);assert.equal(s.c.roomInfo.binding.saving,false);
});
test('bound room or missing binding privilege cannot open owner selection',()=>{
  const s=screen();s.c._openBindRoomOwner({...room(),ownerId:'already'});assert.equal(s.events.length,0);
  s.c._openBindRoomOwner({...room(),ownerName:'已有业主'});assert.equal(s.events.length,0);
  s.permission(false);s.c._openBindRoomOwner(room());assert.equal(s.events.length,0);assert.equal(s.posts.length,0);
});
test('community change or foreign owner prevents binding',()=>{
  for(const mode of ['community','owner']){
    const s=screen();s.c._openBindRoomOwner(room());if(mode==='community')s.community('other');
    s.handlers.chooseOwner({ownerId:'owner',communityId:mode==='owner'?'other':'community'});assert.equal(s.posts.length,0);
  }
});
test('canceling final confirmation changes no ownership',()=>{
  const s=screen();s.c._openBindRoomOwner(room());s.confirm(false);s.handlers.chooseOwner({ownerId:'owner'});assert.equal(s.posts.length,0);
});
test('malformed or refused result is not presented as successful binding',()=>{
  for(const body of ['not-json',JSON.stringify({code:409,msg:'房屋已绑定'}),JSON.stringify({})]){
    const s=screen();s.c._openBindRoomOwner(room());s.handlers.chooseOwner({ownerId:'owner'});
    s.posts[0][4](body,{status:200});assert.equal(s.c.roomInfo.binding.saving,false);assert.ok(!s.toasts.some(x=>x==='房产绑定成功'));
  }
});
test('late success after switching community does not refresh another community as the old room',()=>{
  const s=screen();s.c._openBindRoomOwner(room());s.handlers.chooseOwner({ownerId:'owner'});s.community('other');
  s.posts[0][4](JSON.stringify({code:0}),{status:200});assert.equal(s.refreshes.length,0);
});
test('property binding action is in room management instead of owner list',()=>{
  const html=fs.readFileSync(require.resolve('../public/pages/property/room/room.html'),'utf8');
  const owners=fs.readFileSync(require.resolve('../public/pages/property/listOwner/listOwner.html'),'utf8');
  assert.ok(html.includes('_openBindRoomOwner(room)'));assert.ok(html.includes('property/searchOwner'));assert.ok(!owners.includes('_openAddOwnerRoom(owner)'));
});
