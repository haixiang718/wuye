'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const policy = require('../public/js/local-features');

const room = '/admin.html#/pages/property/roomCreateFee';
const car = '/admin.html#/pages/property/carCreateFee';
const clone = value => JSON.parse(JSON.stringify(value));
function menus() {
  return [{id:'fees',name:'费用管理',active:true,seq:1,childs:[
    {href:room,name:'房屋收费',isShow:'Y',active:false,seq:1},
    {href:car,name:'车辆收费',isShow:'Y',active:true,seq:2}
  ]},{id:'assets',name:'资产管理',active:false,seq:2,childs:[
    {href:'/admin.html#/pages/property/listOwner',name:'业主信息',isShow:'Y',active:false,seq:1}
  ]}];
}

function load({cached=menus(),href=room,current='fees'}={}) {
  let stored = cached && clone(cached);
  let instance;
  const listeners = {};
  const pending = [];
  const window = {
    location:new URL(href, 'http://127.0.0.1:3000'),localFeatures:policy,
    addEventListener:(event,handler) => {listeners[event]=handler;}
  };
  const vc = window.vc = {
    getData:()=>null,getMenus:()=>stored && policy.filterMenus(clone(stored)),
    setMenus:value=>{stored=policy.filterMenus(clone(value));},
    getCurrentMenu:()=>current,setCurrentMenu:value=>{current=value;},
    notNull:value=>value!==null && value!==undefined,getMenuState:()=> 'ON',
    http:{get:(component,method,params,success)=>pending.push(success)},
    jumpToPage:value=>{window.location=new URL(value,window.location.href);}
  };
  function Vue(options) {
    instance=this;
    Object.assign(this,options.data);
    for (const [name,method] of Object.entries(options.methods)) this[name]=method.bind(this);
    this.$forceUpdate=()=>{};
    this.mount=()=>options.mounted.call(this);
  }
  vm.runInNewContext(fs.readFileSync(require.resolve('../public/components/frame/menu/menu.js'),'utf8'),{
    window,location:window.location,URL,Vue,document:{getElementById:()=>({})},
    vcFramework:{eleResize:{on(){}},_fix_height(){}},console
  });
  instance.mount();
  return {instance,window,listeners,pending,stored:()=>clone(stored),current:()=>current};
}
function selected(items) {
  return items.flatMap(group=>(group.childs||[]).filter(child=>child.active).map(child=>child.name));
}

test('refresh restores room fees from URL even when the cached selection is car fees',()=>{
  const app=load();
  assert.deepEqual(selected(app.instance.menus),['房屋收费']);
  assert.deepEqual(selected(app.stored()),['房屋收费']);
  assert.deepEqual(app.instance.menus.filter(group=>group.active).map(group=>group.id),['fees']);
});

test('clicking a fee page persists the selection for the next reload',()=>{
  const app=load({href:car});
  app.instance._gotoPage(room);
  assert.deepEqual(selected(app.stored()),['房屋收费']);
  const refreshed=load({cached:app.stored(),href:app.window.location.href});
  assert.deepEqual(selected(refreshed.instance.menus),['房屋收费']);
});

test('browser back and forward update the selected child and open its parent group',()=>{
  const app=load();
  const target='/admin.html#/pages/property/listOwner';
  app.window.location=new URL(target,'http://127.0.0.1:3000');
  assert.equal(typeof app.listeners.hashchange,'function');
  app.listeners.hashchange();
  assert.deepEqual(selected(app.instance.menus),['业主信息']);
  assert.deepEqual(app.instance.menus.filter(group=>group.active).map(group=>group.id),['assets']);
  assert.equal(app.current(),'assets');
  app.window.location=new URL(car,'http://127.0.0.1:3000');
  app.listeners.hashchange();
  assert.deepEqual(selected(app.instance.menus),['车辆收费']);
});

test('fresh server menus use the URL instead of the saved group or server active flags',()=>{
  const app=load({cached:null,current:'assets'});
  app.pending[0](JSON.stringify(menus()));
  assert.deepEqual(selected(app.instance.menus),['房屋收费']);
  assert.deepEqual(app.instance.menus.filter(group=>group.active).map(group=>group.id),['fees']);
  assert.equal(app.current(),'fees');
});

test('menu route matching ignores query parameters and clears other selected children',()=>{
  const cached=menus();cached[1].active=true;cached[1].childs[0].active=true;
  const app=load({cached,href:room+'?roomId=local-test'});
  assert.deepEqual(selected(app.instance.menus),['房屋收费']);
  assert.deepEqual(app.instance.menus.filter(group=>group.active).map(group=>group.id),['fees']);
});

test('detail pages without a visible menu retain the collection menu selection',()=>{
  const cached=menus();cached[0].childs[0].active=true;cached[0].childs[1].active=false;
  const app=load({cached,href:'/admin.html#/pages/property/owePayFeeOrder?payObjId=local-test'});
  assert.deepEqual(selected(app.instance.menus),['房屋收费']);
});
