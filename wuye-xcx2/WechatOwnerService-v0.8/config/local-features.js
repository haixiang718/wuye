/* Local edition boundaries shared by navigation, menus and request wrappers. */
'use strict';
const disabledPages = new Set(["activites", "activitesdetail", "activitydetail", "activityes", "applicationkey", "applicationkeylocation", "applicationkeyprogress", "applicationkeyuser", "appraiserepair", "auditcomplaintorder", "complaint", "complaintdetail", "complaintfinish", "complainthandle", "complaintlist", "complaintorder", "complaintorderdetail", "excuteinspection", "excuteoneinspection", "inspection", "machinetranslatelog", "myapplicationkey", "myrepair", "notice", "opendoor", "poutofstoragerequest", "purchase", "purchaselist", "purchaserequest", "purchasereview", "purchasingschedule", "repair", "repairadd", "repairdetail", "repairdispatch", "repairdispatchfinish", "repairhandle", "repairlist", "repairorder", "repairpay", "resourcestoremanage", "viewapplicationkeyuser", "viewcomplaint", "visitorapplicationkey"]);
const disabledServices = new Set([
  'repair','ownerrepair','repairsetting','complaint','notice','advert','activities','activitiesrule','activitiestype',
  'activitiesbeautifulstaff','visit','inspectionplan','inspectionplanstaff','inspectionpoint','inspectionroute',
  'inspectionroutepoint','inspectiontask','inspectiontaskdetail','purchase','purchaseapply','collection',
  'resourcestore','resourcestoretypemanage','resourcesupplier','storehouse','machine','machineauth',
  'machinetranslate','machinerecord','applicationkey','addownerrepair'
]);
['family','familylist','hireroom','applyparking','applyroom','applyroomdetail','roomrenovation','roomrenovationdetail',
 'myrenovation','myroomrenovationrecord','myroomrenovationrecorddetail','contractfee','contractfeelist','contractowefee',
 'questionanswer','questionanswerdetail','callproperty'].forEach(page=>disabledPages.add(page));
const parkingOperations = new Set(['machinetranslate.machinegetfreeparkingspace', 'machinetranslate.machineuploadcarlog', 'machinetranslate.machineroadgateopen', 'machinetranslate.machineheartbeart', 'machinetranslate.machinegettaskinfo', 'machinetranslate.machinecmdresult']);
const disabledOperations = new Set([
  'index.queryindextodotask','index/queryindextodotask',
  'audituser.listaudithistoryorders','audituser/listaudithistoryorders',
  'audituser.listauditcomplaints','audituser.listaudithistorycomplaints',
  'audituser.listitemoutauditorders','audituser.listitemoutaudithistoryorders'
]);
function partsOf(value) {
  let route = String(value || '');
  if (route.includes('#')) route = route.slice(route.indexOf('#') + 1);
  route = route.replace(/^https?:\/\/[^/]+/i, '').split('?')[0];
  for (let i = 0; i < 3; i++) {
    try { const decoded = decodeURIComponent(route); if (decoded === route) break; route = decoded; }
    catch (_) { return []; }
  }
  const parts = [];
  route.toLowerCase().replace(/\\/g, '/').split('/').forEach(part => {
    if (part === '..') parts.pop();
    else if (part && part !== '.') parts.push(part);
  });
  return parts;
}
function isDisabledRoute(value) {
  const parts = partsOf(value);
  if (parts[0] === 'pages') parts.shift();
  return disabledPages.has(parts[0]);
}
function isDisabledApi(value) {
  const parts = partsOf(value);
  if (parts[0] !== 'app' && parts[0] !== 'api' && parts[0] !== 'callcomponent') return false;
  parts.shift();
  if (parts[0] === 'upload') parts.shift();
  const operation = parts.join('/');
  if (parkingOperations.has(operation.replace(/\//g, '.'))) return false;
  if (operation === 'machine.uploadownerphoto' || operation === 'machine/uploadownerphoto') return false;
  return disabledOperations.has(operation) || disabledServices.has((parts[0] || '').split('.')[0]);
}
function filterMenus(menus) {
  return (Array.isArray(menus) ? menus : []).filter(menu => menu && !isDisabledRoute(menu.href || menu.url)).map(menu => {
    const copy = Object.assign({}, menu);
    if (Array.isArray(menu.childs)) copy.childs = filterMenus(menu.childs);
    return copy;
  });
}
function filterMenuPages(pages) {
  const filtered = {};
  Object.keys(pages || {}).forEach(key => {
    const menus = filterMenus(pages[key]);
    if (menus.length) filtered[key] = menus;
  });
  return filtered;
}
function blockRequest(options) {
  if (!isDisabledApi(options.url)) return false;
  const error = {code:'FEATURE_DISABLED', errMsg:'request:fail FEATURE_DISABLED'};
  if (options.fail) options.fail(error);
  if (options.complete) options.complete(error);
  return true;
}
function install(runtime) {
  if (!runtime || typeof runtime.addInterceptor !== 'function' || runtime.__localFeaturesInstalled) return;
  runtime.__localFeaturesInstalled = true;
  ['navigateTo','redirectTo','reLaunch','switchTab'].forEach(name => runtime.addInterceptor(name, {
    invoke(options) {
      if (!isDisabledRoute(options.url)) return;
      const error = {code:'FEATURE_DISABLED', errMsg:name + ':fail FEATURE_DISABLED'};
      if (options.fail) options.fail(error);
      if (options.complete) options.complete(error);
      return false;
    }
  }));
  runtime.addInterceptor('request', {invoke(options) { if (blockRequest(options)) return false; }});
}
module.exports = {isDisabledRoute,isDisabledApi,filterMenus,filterMenuPages,blockRequest,install};
