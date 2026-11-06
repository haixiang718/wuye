/* Run with: node --test test/local-features.test.cjs. No uni-app build is implied. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const jsonc = source => JSON.parse(source.replace(/("(?:\\.|[^"\\])*")|\/\/[^\n]*|\/\*[\s\S]*?\*\//g, (match,string) => string || ''));
function loadApi(file, exports, dependencies = {}) {
  let source = read(file).replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g, '').replace(/export function/g, 'function');
  const context = {module:{exports:{}}, console, ...dependencies};
  vm.runInNewContext(source + '\nmodule.exports = {' + exports.join(',') + '};', context);
  return context.module.exports;
}
const owner = fs.existsSync(path.join(root, 'core/vcFramework.js'));

test('disabled routes are absent from registered pages and preserved routes remain', () => {
  const policy = require('../config/local-features');
  const pages = jsonc(read('pages.json')).pages.map(page=>page.path);
  assert.ok(pages.length > 10);
  for (const route of pages) assert.equal(policy.isDisabledRoute(route), false, route);
  const keep = owner ? ['pages/roomFeeListNew/roomFeeListNew','pages/payParkingFeeList/payParkingFeeList','pages/viewBindOwner/viewBindOwner','pages/tempParkingFee/tempParkingFee'] : ['pages/roomPayFee/roomPayFee','pages/meterReading/meterReading','pages/roomRenovation/roomRenovation'];
  for (const route of keep) assert.ok(pages.includes(route), route);
  for (const route of ['/pages/repair/repair','../complaint/complaint','/PAGES/%6eotice/index','/pages/applicationKey/applicationKey','/pages/resourceStoreManage/resourceStoreManage']) assert.equal(policy.isDisabledRoute(route), true, route);
});

test('navigation and request boundaries block disabled operations and preserve core capabilities', () => {
  const policy = require('../config/local-features');
  const interceptors = {};
  policy.install({addInterceptor:(name, handler)=>interceptors[name]=handler});
  for (const name of ['navigateTo','redirectTo','reLaunch','switchTab']) {
    assert.equal(interceptors[name].invoke({url:'/pages/repair/repair'}), false);
    assert.notEqual(interceptors[name].invoke({url:'/pages/roomFeeListNew/roomFeeListNew'}), false);
  }
  for (const url of ['/app/advert.listAdvertPhoto','http://127.0.0.1:8012/app/inspectionTask.listInspectionTasks','/app/machine/openDoor','/app/auditUser.listAuditComplaints','/callComponent/resourceStore.listStorehouses']) {
    let failure;
    assert.equal(interceptors.request.invoke({url,fail:error=>failure=error}), false, url);
    assert.equal(failure.code,'FEATURE_DISABLED');
  }
  for (const url of ['/app/owner.uploadOwnerPhoto','/app/machine.uploadOwnerPhoto','/app/fee.queryFee','/app/payment/toPay','/app/wechat/get','/app/parkingSpace.queryParkingSpacesByOwner','/app/meterWater/queryPreMeterWater','/app/roomRenovation/queryRoomRenovation']) assert.notEqual(interceptors.request.invoke({url}), false, url);
});

test('local H5 proxy and non-H5 defaults never use a demo backend', () => {
  const manifest = jsonc(read('manifest.json'));
  for (const prefix of ['/app','/callComponent']) assert.equal(manifest.h5.devServer.proxy[prefix].target,'http://127.0.0.1:8012');
  assert.doesNotMatch(read('conf/config.js'), /(?:baseUrl|commonBaseUrl)\s*=\s*['"]https?:\/\/(?!127\.0\.0\.1)/);
});

test('homepage and personal menu do not request or advertise disabled modules', () => {
  const home = read('pages/index/index.vue');
  assert.doesNotMatch(home, /loadAdvert|loadActivit|getActivitiTitle|_loadAd\(/);
  const personal = read('pages/my/my.vue').split('</template>')[0];
  assert.doesNotMatch(personal, /_repairFinish|_complaintFinish|myComplaint|myRepair|_machineTranslate/);
});

test('real menu API filters disabled server/static entries and retains fee, parking and assets', async () => {
  const policy = require('../config/local-features');
  if (owner) {
    const api = loadApi('api/index/indexApi.js',['getCategoryList'],{localFeatures:policy});
    const menus = Object.values(api.getCategoryList()).flat();
    for (const item of menus) assert.equal(policy.isDisabledRoute(item.href), false, item.href);
    for (const keep of ['/pages/roomFeeListNew/roomFeeListNew','/pages/payParkingFeeList/payParkingFeeList','/pages/tempParkingFee/tempParkingFee']) assert.ok(menus.some(item=>item.href===keep), keep);
    assert.equal(menus.length,3);
    assert.doesNotMatch(read('pages/my/my.vue').split('</template>')[0],/房屋装修/);
    for (const page of ['family','familyList','hireRoom','applyparking','applyRoom','roomRenovation','myRenovation','contractFeeList','questionAnswer']) assert.equal(policy.isDisabledRoute('/pages/'+page+'/index'),true,page);
  } else {
    const api = loadApi('api/index/index.js',['loadCategoryMenus'],{localFeatures:policy});
    const urls = ['/pages/repairOrder/repairOrder','/pages/roomPayFee/roomPayFee','/pages/meterReading/meterReading'];
    const menus = await api.loadCategoryMenus({url:{queryMenus:'/app/query.menu.info'},context:{get:options=>options.success({data:[{childs:urls.map((href,seq)=>({href,seq,isShow:'Y',name:href}))}]})}},{});
    const entries=Object.values(menus).flat();
    assert.equal(entries.length,2);
    assert.deepEqual(Array.from(entries,item=>item.href),urls.slice(1));
  }
});


test('legacy native HTTP factory rejects disabled work before contacting runtime', async t => {
  const originalWx = global.wx;
  const contacted = [];
  global.wx = {request:options => {contacted.push(options.url); options.success({statusCode:200});}};
  t.after(() => {global.wx = originalWx;});
  const factory = require('../factory/HttpFactory');
  await assert.rejects(factory.getSync({},'/app/notice.listNotices',{}), error=>error.code==='FEATURE_DISABLED');
  await assert.rejects(factory.postSync({},'/app/ownerRepair.repairFinish',{}), error=>error.code==='FEATURE_DISABLED');
  assert.deepEqual(contacted, []);
  assert.equal((await factory.postSync({},'/app/owner.uploadOwnerPhoto',{})).statusCode,200);
  assert.deepEqual(contacted, ['/app/owner.uploadOwnerPhoto']);
});

test('parking machine callbacks are precise exceptions to disabled door management', () => {
  const policy = require('../config/local-features');
  const parkingMethods = ['machineGetFreeParkingSpace','machineUploadCarLog','machineRoadGateOpen','machineHeartbeart','machineGetTaskInfo','machineCmdResult'];
  for (const prefix of ['app','api']) for (const separator of ['.','/']) {
    for (const method of parkingMethods) assert.equal(policy.isDisabledApi('/' + prefix + '/machineTranslate' + separator + method), false, method);
    for (const method of ['machineUploadFaceLog','machineQueryUserInfo']) assert.equal(policy.isDisabledApi('/' + prefix + '/machineTranslate' + separator + method), true, method);
  }
});


test('disabled home todo and procurement history APIs are blocked without hiding assets or audit setup', () => {
  const policy = require('../config/local-features');
  for (const prefix of ['app','api','callComponent']) for (const separator of ['.','/']) {
    for (const operation of [['index','queryIndexTodoTask'],['auditUser','listAuditHistoryOrders']]) {
      const url = '/' + prefix + '/' + operation.join(separator);
      assert.equal(policy.isDisabledApi(url), true, url);
    }
  }
  for (const url of ['/callComponent/indexContext/getData','/app/auditUser.listAuditUsers']) assert.equal(policy.isDisabledApi(url), false, url);
});
