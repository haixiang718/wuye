'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), vm = require('node:vm');
function screen(title = '业主') {
  const requests = [], toasts = [], events = [], handlers = {}, modalEvents = {}, modalCalls = [];
  let communityId = 'current-community', definition;
  const vc = {
    propTypes: {string: ''},
    extends(def) {definition = def; this.component = Object.assign({}, def.data, def.methods);},
    getCurrentCommunity: () => ({communityId}),
    on(component, event, handler) {handlers[event] = handler;},
    getAttrSpec: (name, done) => done([]),
    toast: value => toasts.push(value),
    emit: (...args) => events.push(args),
    validate: {validate: () => true},
    http: {
      post(...args) {requests.push(args);},
      get(...args) {requests.push(args);}
    }
  };
  const context = {window:{vc}, $props:{componentTitle:title, notifyLoadDataComponentName:'listOwner'},
    $: () => ({modal(action) {modalCalls.push(action);}, on(event, handler) {modalEvents[event] = handler;}}), console:{log() {}}, navigator:{}};
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(require.resolve('../public/components/property/addOwner/addOwner.js'),'utf8'),context);
  context.$that = vc.component;
  Object.assign(vc.component.addOwnerInfo,{name:'测试业主',link:'19900000999',idCard:'11010519491231002X',sex:'0',ownerId:-1});
  return {component:vc.component, requests,toasts,events,handlers,modalEvents,modalCalls,
    initEvents: () => definition._initEvent(), setCommunity: id => {communityId = id;}};
}
test('room selections persist across search pages and do not duplicate', () => {
  const {component:c} = screen();
  c.toggleOwnerRoom({roomId:'r1',floorNum:'1',unitNum:'1',roomNum:'101'});
  c.toggleOwnerRoom({roomId:'r2',floorNum:'2',unitNum:'1',roomNum:'201'});
  assert.equal(c.addOwnerInfo.selectedRooms.length,2);
  assert.equal(c.isOwnerRoomSelected('r1'),true);
  c.toggleOwnerRoom({roomId:'r1'});
  assert.equal(c.addOwnerInfo.selectedRooms.length,1);
  c.removeOwnerRoom('r2');
  assert.equal(c.addOwnerInfo.selectedRooms.length,0);
});
test('one request saves all selected rooms and suppresses double submission', () => {
  const {component:c,requests} = screen();
  c.toggleOwnerRoom({roomId:'r1'});c.toggleOwnerRoom({roomId:'r2'});
  c.saveOwnerInfo();c.saveOwnerInfo();
  assert.equal(requests.length,1);
  const payload=JSON.parse(requests[0][2]);
  assert.deepEqual(payload.roomIds,['r1','r2']);
  assert.equal(payload.communityId,'current-community');
  assert.equal(payload.selectedRooms,undefined);
  assert.equal(payload.mediaStreamTrack,undefined);
});
test('save failure retains entered identity and rooms and enables retry', () => {
  const {component:c,requests} = screen();
  c.toggleOwnerRoom({roomId:'r1'});c.saveOwnerInfo();
  requests[0][4](JSON.stringify({code:409,msg:'房屋已绑定'}),{status:200});
  assert.equal(c.addOwnerInfo.name,'测试业主');
  assert.equal(c.addOwnerInfo.selectedRooms.length,1);
  assert.equal(c.addOwnerInfo.saving,false);
  c.saveOwnerInfo();assert.equal(requests.length,2);
});
test('HTTP failure also releases busy state without losing fields', () => {
  const {component:c,requests} = screen();
  c.toggleOwnerRoom({roomId:'r1'});c.saveOwnerInfo();
  requests[0][5]('网络连接失败');
  assert.equal(c.addOwnerInfo.saving,false);
  assert.equal(c.addOwnerInfo.selectedRooms[0].roomId,'r1');
});
test('binding requires identity card for subsequent WeChat verification', () => {
  const {component:c,requests,toasts} = screen();
  c.toggleOwnerRoom({roomId:'r1'});c.addOwnerInfo.idCard='';
  c.saveOwnerInfo();assert.equal(requests.length,0);
  assert.match(toasts.join(' '),/身份证/);
});
test('member saving never carries room selections and unbound owners stay compatible', () => {
  for (const title of ['成员','业主']) {
    const {component:c,requests} = screen(title);
    c.addOwnerInfo.selectedRooms=title==='成员'?[{roomId:'forged-member-room'}]:[];
    c.saveOwnerInfo();
    assert.equal(JSON.parse(requests[0][2]).roomIds,undefined);
  }
});
test('stale room search responses do not replace current community options', () => {
  const {component:c,requests} = screen();
  c.loadOwnerRooms(1);c.loadOwnerRooms(2);
  requests[1][3](JSON.stringify({rooms:[{roomId:'new'}],total:11,records:2}));
  requests[0][3](JSON.stringify({rooms:[{roomId:'old'}],total:11,records:2}));
  assert.equal(c.addOwnerInfo.roomOptions[0].roomId,'new');
});

test('queries only current community available rooms and preserves selections between pages', () => {
  const {component:c,requests} = screen();
  c.toggleOwnerRoom({roomId:'r1'});
  c.addOwnerInfo.roomFloorNum='3';c.addOwnerInfo.roomNum='101';
  c.loadOwnerRooms(2);
  assert.equal(requests[0][0],'searchRoom');assert.equal(requests[0][1],'listRoom');
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0][2].params)),{
    communityId:'current-community',page:2,row:10,roomFlag:'2',floorNum:'3',roomNum:'101'
  });
  requests[0][3](JSON.stringify({rooms:[{roomId:'r2'}],total:11,records:2}));
  assert.equal(c.isOwnerRoomSelected('r1'),true);
  assert.equal(c.addOwnerInfo.roomPage,2);assert.equal(c.addOwnerInfo.roomPages,2);
});

test('room query uses legacy total row count and records page count', () => {
  const {component:c,requests} = screen();
  c.loadOwnerRooms(2);
  requests[0][3](JSON.stringify({rooms:[{roomId:'r2'}],total:23,records:3}));
  assert.equal(c.addOwnerInfo.roomRecords,23);
  assert.equal(c.addOwnerInfo.roomPages,3);
  assert.equal(c.addOwnerInfo.roomPage,2);
});

test('empty legacy room response omits rooms and clears previous pagination without error', () => {
  const {component:c,requests,toasts} = screen();
  c.toggleOwnerRoom({roomId:'selected'});
  c.loadOwnerRooms(1);
  requests[0][3](JSON.stringify({rooms:[{roomId:'r1'}],total:23,records:3}));
  c.loadOwnerRooms(1);
  requests[1][3](JSON.stringify({total:0,records:0}));
  assert.equal(c.addOwnerInfo.roomOptions.length,0);
  assert.equal(c.addOwnerInfo.roomRecords,0);
  assert.equal(c.addOwnerInfo.roomPages,0);
  assert.equal(c.addOwnerInfo.roomLoading,false);
  assert.equal(c.isOwnerRoomSelected('selected'),true);
  assert.equal(toasts.length,0);
});

test('community changes discard options and selected rooms and invalidate callbacks', () => {
  const s=screen(), c=s.component;
  c.toggleOwnerRoom({roomId:'r1'});c.loadOwnerRooms(1);
  s.setCommunity('other-community');
  s.requests[0][3](JSON.stringify({rooms:[{roomId:'old'}],records:1,total:1}));
  assert.equal(c.addOwnerInfo.roomOptions.length,0);
  assert.equal(c.addOwnerInfo.selectedRooms.length,0);
  assert.equal(c.addOwnerInfo.roomLoading,false);
  c.loadOwnerRooms(1);
  assert.equal(s.requests[1][2].params.communityId,'other-community');
});

test('save does not submit room ids selected before switching community', () => {
  const s=screen(), c=s.component;
  c.toggleOwnerRoom({roomId:'r1'});s.setCommunity('other-community');c.saveOwnerInfo();
  assert.equal(s.requests.length,0);assert.equal(c.addOwnerInfo.selectedRooms.length,0);
  assert.match(s.toasts.join(' '),/小区/);
});

test('selection has a 50 room limit but allows removing a selected room', () => {
  const {component:c,toasts} = screen();
  for(let i=0;i<51;i++) c.toggleOwnerRoom({roomId:'r'+i});
  assert.equal(c.addOwnerInfo.selectedRooms.length,50);assert.match(toasts.join(' '),/50/);
  c.toggleOwnerRoom({roomId:'r0'});assert.equal(c.addOwnerInfo.selectedRooms.length,49);
});

test('payload preserves attributes and photo without serializing camera or picker state', () => {
  const {component:c,requests} = screen();
  const camera={stop() {}}; camera.circular=camera;
  Object.assign(c.addOwnerInfo,{mediaStreamTrack:camera,ownerPhoto:'data:image/jpeg;base64,photo',
    attrs:[{specCd:'test-spec',value:'test-value'}]});
  c.toggleOwnerRoom({roomId:'r1'});c.saveOwnerInfo();
  const payload=JSON.parse(requests[0][2]);
  assert.equal(payload.ownerPhoto,'data:image/jpeg;base64,photo');assert.equal(payload.attrs[0].value,'test-value');
  for(const key of ['mediaStreamTrack','videoPlaying','saving','roomOptions','roomLoading','roomCommunityId']) {
    assert.equal(payload[key],undefined,key);
  }
});

test('opening a modal resets previous state; closing is prevented only while saving', () => {
  const s=screen(), c=s.component;s.initEvents();
  c.toggleOwnerRoom({roomId:'r1'});c.loadOwnerRooms(1);
  s.handlers.openAddOwnerModal(-1);
  assert.equal(c.addOwnerInfo.name,'');assert.equal(c.addOwnerInfo.selectedRooms.length,0);
  assert.equal(c.addOwnerInfo.ownerId,-1);
  s.requests[0][3](JSON.stringify({rooms:[{roomId:'old-modal'}],total:1,records:1}));
  assert.equal(c.addOwnerInfo.roomOptions.length,0);
  let prevented=0;c.addOwnerInfo.saving=true;
  s.modalEvents['hide.bs.modal']({preventDefault() {prevented++;}});
  assert.equal(prevented,1);
  c.addOwnerInfo.saving=false;s.modalEvents['hide.bs.modal']({preventDefault() {prevented++;}});
  assert.equal(prevented,1);
});

test('room lookup errors leave selected rooms available for retry', () => {
  const {component:c,requests,toasts} = screen();
  c.toggleOwnerRoom({roomId:'r1'});c.loadOwnerRooms(1);requests[0][4]('查询失败');
  assert.equal(c.addOwnerInfo.roomLoading,false);assert.equal(c.isOwnerRoomSelected('r1'),true);
  assert.ok(toasts.length);
});

test('successful save clears selections and refreshes owners once', () => {
  const {component:c,requests,events,modalCalls} = screen();
  c.toggleOwnerRoom({roomId:'r1'});c.saveOwnerInfo();requests[0][4](JSON.stringify({code:0}));
  assert.equal(c.addOwnerInfo.saving,false);assert.equal(c.addOwnerInfo.selectedRooms.length,0);
  assert.deepEqual(JSON.parse(JSON.stringify(events)),[['listOwner','listOwnerData',{}]]);
  assert.ok(modalCalls.includes('hide'));
});
