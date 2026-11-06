'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const vm = require('node:vm');
const parkingMethods = ['machineGetFreeParkingSpace','machineUploadCarLog','machineRoadGateOpen','machineHeartbeart','machineGetTaskInfo','machineCmdResult'];

test('default proxy target is local', () => {
  delete process.env.BACKEND_URL;
  delete require.cache[require.resolve('../app')];
  assert.equal(require('../app').get('backendTarget'), 'http://127.0.0.1:8012');
});

test('real HTTP requests reject disabled pages, components and services before proxying', async t => {
  const forwarded = [];
  const upstream = http.createServer((req, res) => { forwarded.push(req.url); res.end('local upstream'); });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  process.env.BACKEND_URL = 'http://127.0.0.1:' + upstream.address().port;
  delete require.cache[require.resolve('../app')];
  const app = require('../app');
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => { server.close(); upstream.close(); delete process.env.BACKEND_URL; });
  const request = path => new Promise((resolve, reject) => {
    http.get({ hostname: '127.0.0.1', port: server.address().port, path }, res => {
      let body = ''; res.on('data', chunk => body += chunk); res.on('end', () => resolve({status: res.statusCode, body}));
    }).on('error', reject);
  });
  for (const path of [
    '/pages/property/repairPoolManage.html', '/pages/property/repairPoolManage/repairPoolManage.js',
    '/components/property/addMachine.html', '/COMPONENTS/property/%61ddMachine.js',
    '/pages/common/noticeManage.html', '/callComponent/ownerRepairManage/list',
    '/callComponent/%72epair.listRepairSettings', '/APP/INSPECTIONTASK.listInspectionTasks',
    '/app/purchase/purchaseApply', '/callComponent/resourceStore.listStorehouses',
    '/callComponent/activitiesType/queryActivitiesType', '/callComponent/nav/getNavData',
    '/app/reportFeeMonthStatistics/queryRepair', '/callComponent/upload/addAdvert/upload',
    '/app/auditUser.listItemOutAuditHistoryOrders',
    '/components/property/simplifyOwnerAccessContol/simplifyOwnerAccessContol.js',
    '/pages/common/inAndOutStep/inAndOutStep.html', '/advertVedio.html',
    '/app/reportFeeMonthStatistics.queryRepair', '/app/auditUser.listAuditComplaints', '/app/machineTranslate.machineUploadFaceLog', '/app/machineTranslate.machineQueryUserInfo',
    '/app/bigScreen/getAssetsRepair', '/app/bigScreen/getAssetOpenDoor', '/app/bigScreen/getAssetInspection',
    '/bigScreen/js/box2.js',
    '/components/property/indexArrears/indexArrears.js',
    '/pages/admin/myAuditOrders/myAuditOrders.html',
    '/pages/admin/myAuditHistoryOrders/myAuditHistoryOrders.js',
    '/pages/goods/productManage/productManage.html',
    '/pages/admin/shopManage/shopManage.html',
    '/pages/admin/rentingPoolManage/rentingPoolManage.js',
    '/pages/proxy/junkRequirementManage/junkRequirementManage.html',
    '/app/renting.queryRentingPools', '/app/junkRequirement.listJunkRequirements',
    '/app/groupBuy/queryGroupBuys', '/app/product/queryProducts',
    '/app/storeOrder/queryStoreOrders', '/callComponent/shopManage/list',
    '/app/owner.listOwnerMachines', '/app/owner/listOwnerMachines', '/goods/unifieldOrder',
    '/callComponent/myAuditOrders/list', '/callComponent/myAuditOrders/audit',
    ...['app','api','callComponent'].flatMap(prefix => ['.','/'].flatMap(separator => [
      '/' + prefix + '/index' + separator + 'queryIndexTodoTask',
      '/' + prefix + '/auditUser' + separator + 'listAuditHistoryOrders'
    ]))
  ]) {
    const response = await request(path);
    assert.equal(response.status, 404, path);
    assert.match(response.body, /FEATURE_DISABLED/, path);
  }
  assert.deepEqual(forwarded, []);
  for (const path of [
    '/app/fee.listFees', '/callComponent/payFee/pay', '/callComponent/wechatSmsTemplateManage/list',
    '/app/notifyFee/notify', '/callComponent/publicWeChatManage/list', '/app/parkingArea.listParkingAreas',
    '/callComponent/upload/uploadOwnerPhoto/upload', '/app/machine.uploadOwnerPhoto',
    '/callComponent/download/getFile', '/callComponent/config/getConfig',
    '/callComponent/indexContext/getData', '/app/auditUser.listAuditUsers',
    '/app/store.queryStores', '/callComponent/shops/query',
    ...parkingMethods.flatMap(method=>['/app/machineTranslate.' + method, '/app/machineTranslate/' + method])
  ]) {
    const response = await request(path);
    assert.equal(response.status, 200, path);
    assert.equal(response.body, 'local upstream', path);
  }
  assert.equal((await request('/admin.html')).status, 200);
  assert.equal((await request('/pages/property/shops/shops.html')).status, 200);
  assert.equal((await request('/pages/property/publicWeChatManage/publicWeChatManage.html')).status, 200);
});

test('dashboard entrypoints load policy and request only retained analytics', () => {
  const path = require('node:path');
  for (const file of ['public/bigScreen/index.html','public/bigScreen/public.html']) {
    const source = fs.readFileSync(require.resolve('../' + file), 'utf8');
    assert.ok(source.indexOf('/js/local-features.js') >= 0, file);
    assert.ok(source.indexOf('/js/local-features.js') < source.indexOf('/vcCore/vcFramework.js'), file);
    assert.doesNotMatch(source, /todayInpection|id="machineCount"|id="box2"|id="box3_right"|reportRepair/);
    const called = [];
    for (const match of source.matchAll(/<script[^>]*src="js\/([^"/]+\.js)"/g)) {
      if (!['public.js','box1.js','box2.js','box3.js','box4.js','box5.js'].includes(match[1])) continue;
      vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../public/bigScreen/js', match[1]), 'utf8'), {
        vc:{getCurrentCommunity:()=>({communityId:'local',name:'local'}), http:{apiGet:url=>called.push(url)}},
        document:{getElementById:()=>({})},window:{},console
      });
    }
    assert.ok(called.includes('/bigScreen/getAssets'), file);
    assert.ok(called.some(url=>/fee|payment/i.test(url)), file);
    assert.ok(!called.some(url=>/repair|inspection|opendoor/i.test(url)), file + ': ' + called.join(','));
  }
});

test('retained screens have no disabled shortcuts or external tracking', () => {
  const policy = require('../public/js/local-features');
  for (const file of [
    'public/components/frame/nav/nav.html',
    'public/pages/property/indexContext/indexContext.html',
    'public/pages/property/simplifyAcceptance/simplifyAcceptance.html',
    'public/pages/property/simplifyAcceptanceTT/simplifyAcceptanceTT.html'
  ]) {
    const source = fs.readFileSync(require.resolve('../' + file), 'utf8');
    assert.doesNotMatch(source, /moreNoticeUrl|simplifyOwnerRepair|simplifyOwnerComplaint|simplifyOwnerAccessContol/);
    for (const match of source.matchAll(/<vc:create path="([^"]+)"/g)) {
      assert.equal(policy.isDisabled('/components/' + match[1]), false, file + ': ' + match[1]);
    }
  }
  for (const ext of ['js', 'html']) {
    assert.doesNotMatch(fs.readFileSync(require.resolve('../public/components/frame/commonTop/commonTop.' + ext), 'utf8'), /hm\.baidu\.com/);
  }
});

test('procurement group is removed while shared audit-user configuration remains', () => {
  const policy = require('../public/js/local-features');
  const shared = '/admin.html#/pages/common/addAuditUserStep';
  const menus = [
    {id:'800201906011', childs:[
      {href:'/admin.html#/pages/admin/myAuditOrders',isShow:'Y'},
      {href:'/admin.html#/pages/admin/myAuditHistoryOrders',isShow:'Y'},
      {href:shared,isShow:'N'}
    ]},
    {id:'system',childs:[{href:'/admin.html#/pages/common/auditUserManage'},{href:shared,isShow:'N'}]}
  ];
  assert.deepEqual(policy.filterMenus(menus), [menus[1]]);
  assert.equal(policy.isDisabled(shared), false);
});

test('every retained template avoids disabled nested components', () => {
  const path = require('node:path');
  const policy = require('../public/js/local-features');
  function inspect(directory) {
    for (const entry of fs.readdirSync(directory, {withFileTypes:true})) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) { inspect(file); continue; }
      const route = '/' + path.relative(path.join(__dirname, '../public'), file).replaceAll('\\', '/');
      if (!file.endsWith('.html') || policy.isDisabled(route)) continue;
      const source = fs.readFileSync(file, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
      for (const match of source.matchAll(/<vc:create[^>]*path="([^"]+)"/g)) {
        const target = match[1].startsWith('/pages') ? match[1] : '/components/' + match[1].replace(/^\//, '');
        assert.equal(policy.isDisabled(target), false, route + ': ' + match[1]);
      }
    }
  }
  inspect(path.join(__dirname, '../public/pages'));
  inspect(path.join(__dirname, '../public/components'));
});

test('both fresh and cached menus apply policy while preserving fees, wechat and parking', () => {
  const policy = require('../public/js/local-features');
  const menus = [{ id: 'mixed', name: '业务', childs: [
    {href:'/admin.html#/pages/property/repairPoolManage'},
    {href:'/admin.html#/pages/property/listRoomFee'},
    {href:'/admin.html#/pages/property/publicWeChatManage'},
    {href:'/admin.html#/pages/property/listParkingSpace'}
  ]}, {id:'empty', childs:[{href:'/admin.html#/pages/common/noticeManage'}]}];
  const original = JSON.stringify(menus);
  const filtered = policy.filterMenus(menus);
  assert.equal(filtered.length, 1);
  assert.equal(filtered[0].childs.length, 3);
  assert.equal(JSON.stringify(menus), original, 'filter does not mutate upstream/cache data');
  const source = fs.readFileSync(require.resolve('../public/vcCore/vcFramework.js'), 'utf8');
  const start = source.indexOf('vcFramework.setMenus =');
  const end = source.indexOf('vcFramework.setMenuState =', start);
  let stored = original;
  const context = {vcFramework:{}, window:{localFeatures:policy, localStorage:{setItem:(key,value)=>stored=value,getItem:()=>stored}}};
  vm.runInNewContext(source.slice(start,end), context);
  assert.equal(context.vcFramework.getMenus()[0].childs.length, 3, 'old cached menus filtered on read');
  context.vcFramework.setMenus(menus);
  assert.equal(JSON.parse(stored)[0].childs.length, 3, 'fresh menus filtered on write');
  assert.equal(policy.isDisabled('/admin.html#/PAGES/property/%72epairPoolManage'), true);
  assert.equal(policy.isDisabled('/app/fee.listFees?note=repair'), false);
});

test('parking callbacks remain available in app/api dot and slash forms while door operations stay blocked', () => {
  const policy = require('../public/js/local-features');
  for (const prefix of ['app','api']) for (const separator of ['.','/']) {
    for (const method of parkingMethods) assert.equal(policy.isDisabled('/' + prefix + '/machineTranslate' + separator + method), false, method);
    for (const method of ['machineUploadFaceLog','machineQueryUserInfo']) assert.equal(policy.isDisabled('/' + prefix + '/machineTranslate' + separator + method), true, method);
  }
});
