#!/usr/bin/env node
'use strict';

// Real isolated local services only. No OAuth, WeChat sends, menus, payments, or jobs.
// Run from any directory; root refreshes .runtime/browser-cookies.json before use.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const runtime = path.join(root, '.runtime');
const COMMUNITY = '7020181217000001';
const ROOM = '752026102250640002';
const REMARK = 'LOCAL NEW OWNER BINDING TEST ONLY BIND202';
const TIMEOUT = 30000;

function options(argv) {
  const result = {profile:'property', label:'current', write:false,allowKnownMissingAccountTable:false,allowKnownBaselineGaps:false};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--write') result.write = true;
    else if (argv[i] === '--allow-known-missing-account-table') result.allowKnownMissingAccountTable = true;
    else if (argv[i] === '--allow-known-baseline-gaps') result.allowKnownBaselineGaps = true;
    else if (argv[i] === '--help') result.help = true;
    else if (argv[i] === '--profile' || argv[i] === '--label') {
      const key = argv[i].slice(2);
      assert.ok(argv[i + 1] && !argv[i + 1].startsWith('--'), `Missing ${key}`);
      result[key] = argv[++i];
    } else throw new Error('Unknown argument');
  }
  assert.ok(['property','merged'].includes(result.profile), 'Invalid profile');
  assert.match(result.label, /^[A-Za-z0-9_-]{1,64}$/, 'Invalid label');
  return result;
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(path.join(runtime,file),'utf8').replace(/^\uFEFF/,''));
}

function validCode(data) {
  return data && (data.code === undefined || Number(data.code) === 0);
}

function summary(response, data, key) {
  const rows = key === null ? data : data && data[key];
  return {http:response.status(), code:Number.isFinite(Number(data?.code)) ? Number(data.code) : null,
    rows:Array.isArray(rows) ? rows.length : null,
    passed:response.status() === 200 && validCode(data) && Array.isArray(rows)};
}

async function json(response) {
  try { return await response.json(); } catch { return null; }
}

async function main(config) {
  const {chromium,request} = require(path.join(root,'.tools/browser/node_modules/playwright-core'));
  const report = {profile:config.profile,label:config.label,write:config.write,
    startedAt:new Date().toISOString(), checks:[],
    limitations:['Local APP password session exercises the H5 read endpoints; real WeChat OAuth and H5 rendering are not covered.',
      'Rejected malformed update proves validation rejection and unchanged fixture fields, not rollback after a partial database write.',
      'Room API restoration restores submitted business fields; generated audit/order records are retained for database review.']};
  const output = path.join(runtime,`consolidation-runtime-${config.profile}-${config.label}.json`);
  let browser, ownerContext, anonymousContext;
  const save = () => fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
  // Never copy response bodies, arbitrary exception messages, cookies, or credentials to reports/stdout.
  async function check(name, action) {
    try {
      const result = await action();
      report.checks.push({name,...result,passed:result.passed !== false});
    } catch {
      report.checks.push({name,passed:false,error:'Check failed; inspect local service logs privately.'});
    }
    save();
  }
  try {
    browser = await chromium.launch({executablePath:process.env.BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,timeout:TIMEOUT});
    const context = await browser.newContext();
    context.setDefaultTimeout(TIMEOUT);
    context.setDefaultNavigationTimeout(TIMEOUT);
    await context.addCookies(readJson('browser-cookies.json'));
    const page = await context.newPage();
    await page.goto('http://127.0.0.1:3000/admin.html',{waitUntil:'domcontentloaded'});
    await page.waitForFunction(() => window.vc && vc.getCurrentCommunity());
    const query = `communityId=${COMMUNITY}&page=1&row=10`;
    const targets = [
      ['rooms','/callComponent/room/listRoom?'+query+'&roomType=1010301','rooms'],
      ['owners','/callComponent/listOwner/list?'+query+'&ownerTypeCd=1001','owners'],
      ['feeConfig','/callComponent/feeConfigManage/list?'+query,'feeConfigs'],
      ['bills','/app/fee.listBill?'+query+'&curBill=T','data'],
      ['wechatConfig','/app/smallWeChat.listSmallWeChats?'+query,'smallWeChats'],
      ['parking','/callComponent/carInoutManage/list?'+query,'carInouts'],
      ['payments','/callComponent/payFeeManage/list?'+query+'&payObjType=&startTime=&endTime=&userCode=&primeRate=','payFees'],
      ['feeReport','/app/reportFeeMonthStatistics/queryReportFeeSummary?'+query+'&startTime=2026-10-01&endTime=2026-10-31','data'],
      ['contractsGateway','/app/contract/queryContract?'+query,'data'],
      ['tasks','/app/task.listTasks?'+query,'data'],
      ['floors','/callComponent/listFloor/list?'+query,'apiFloorDataVoList']
    ];
    for (const [name,url,key] of targets) await check(name,async () => {
      if (url.startsWith('/app/')) return page.evaluate(({url,key,timeout}) => new Promise(resolve => {
        const timer = setTimeout(() => resolve({passed:false,error:'API callback timed out'}),timeout);
        const finish = result => {clearTimeout(timer);resolve(result);};
        try {
          const parsed = new URL(url,location.origin);
          // Removing '/app' retains '/contract/queryContract' as required by vc.http.
          vc.http.apiGet(parsed.pathname.slice(4),{params:Object.fromEntries(parsed.searchParams)},body => {
            try {
              const data = typeof body === 'string' ? JSON.parse(body) : body;
              const code = Number.isFinite(Number(data?.code)) ? Number(data.code) : null;
              finish({code,rows:Array.isArray(data?.[key]) ? data[key].length : null,
                passed:Array.isArray(data?.[key]) && (data.code === undefined || Number(data.code) === 0)});
            } catch {finish({passed:false,error:'Invalid API response'});}
          },() => finish({passed:false,error:'API request failed'}));
        } catch {finish({passed:false,error:'API call failed'});}
      }),{url,key,timeout:TIMEOUT});
      const response = await context.request.get('http://127.0.0.1:3000'+url,{timeout:TIMEOUT,maxRedirects:0});
      return summary(response,await json(response),key);
    });
    const port = service => config.profile === 'merged' ? 8015 : {acct:8003,common:8005,community:8009,store:8006}[service];
    await check('contractsService',async () => {
      // Empty synthetic scope tests only the count path; it cannot prove that a
      // contract detail SELECT works on the imported legacy schema.
      const response = await context.request.get(`http://127.0.0.1:${port('store')}/contract/queryContract`,
        {params:{page:1,row:10},headers:{'store-id':'TEST-SMOKE'},timeout:TIMEOUT});
      return summary(response,await json(response),'data');
    });
    await check('contractsServiceExistingStore',async () => {
      const response = await context.request.get(`http://127.0.0.1:${port('store')}/contract/queryContract`,
        {params:{page:1,row:10},headers:{'store-id':'402019032924930007'},timeout:TIMEOUT});
      return summary(response,await json(response),'data');
    });
    for (const [name,service,url,payload] of [
      ['accounts','acct','/accountApi/queryAccounts',{page:1,row:10}],
      ['area','common','/areaApi/getArea',{areaLevel:'101',page:1,row:10}]
    ]) await check(name,async () => {
      const response = await context.request.post(`http://127.0.0.1:${port(service)}${url}`,{data:payload,timeout:TIMEOUT});
      return summary(response,await json(response),null);
    });
    await check('communityShards',async () => {
      async function communities(payload) {
        const response = await context.request.post(`http://127.0.0.1:${port('community')}/communityApi/queryCommunitys`,{data:payload,timeout:TIMEOUT});
        const data = await json(response);
        assert.equal(response.status(),200);assert.ok(Array.isArray(data));return data;
      }
      const rows = await communities({page:1,row:1000});
      const ids = rows.map(row => row.communityId);
      assert.ok(ids.length && ids.length < 1000,'Community query truncated or empty');
      const expected = JSON.parse(execFileSync('python',[path.join(__dirname,'consolidation-community-expected.py')],
        {cwd:root,encoding:'utf8',timeout:TIMEOUT,stdio:['ignore','pipe','pipe'],windowsHide:true}));
      assert.deepEqual([...new Set(ids)].sort(),expected,'Cross-shard query differs from original databases');
      for (const parity of [0n,1n]) {
        const id = ids.find(value => /^\d+$/.test(value) && BigInt(value) % 2n === parity);
        assert.ok(id,'Both community shards require a fixture');
        const single = await communities({communityId:id,page:1,row:10});
        assert.equal(single.length,1);assert.equal(single[0].communityId,id);
      }
      return {rows:rows.length,expectedDatabaseEquality:true,evenCommunity:true,oddCommunity:true};
    });

    ownerContext = await request.newContext({timeout:TIMEOUT});
    anonymousContext = await request.newContext({timeout:TIMEOUT});
    const fixture = readJson('wechat-test-fixture.json');
    assert.equal(fixture.label,'LOCAL WECHAT TEST ONLY - NOT A REAL OWNER OR BILL');
    assert.equal(fixture.prefix,'WXTEST20261021');
    assert.equal(fixture.ids.room,'WXTEST20261021R');
    const ownerHeaders = {'app-id':'992020061440050003','transaction-id':'CONSOLIDATION-SMOKE',
      'req-time':new Date().toISOString().replace(/\D/g,'').slice(0,14),'sign':'1234567','user-id':'-1'};
    // Password login uses the APP client to avoid OAuth; reads use the actual H5 client.
    const h5Headers = {...ownerHeaders,'app-id':'992020061452450002'};
    let auth;
    await check('ownerPasswordLogin',async () => {
      const response = await ownerContext.post('http://127.0.0.1:8012/app/loginOwner',{
        headers:ownerHeaders,data:{username:fixture.ownerName,password:fixture.password,loginByPhone:false}});
      auth = await json(response);
      assert.equal(response.status(),200);assert.ok(auth?.token && auth.owner);
      assert.equal(auth.owner.ownerId,fixture.ids.owner);assert.equal(auth.owner.communityId,fixture.communityId);
      return {http:response.status(),fixtureOwner:true};
    });
    const ownerTargets = [
      ['ownerHouses','room.queryRoomsByOwner',{communityId:fixture.communityId,ownerId:fixture.ids.owner},'rooms','roomId',fixture.ids.room],
      ['ownerFees','fee.listFee',{communityId:fixture.communityId,payerObjId:fixture.ids.room,state:'2008001',page:1,row:30},'fees','feeId',fixture.ids.fee]
    ];
    for (const [name,service,params,key,idKey,id] of ownerTargets) {
      const url = 'http://127.0.0.1:8012/app/'+service;
      await check(name,async () => {
        assert.ok(auth?.token);
        const response = await ownerContext.get(url,{params,headers:{...h5Headers,cookie:'_java110_token_='+auth.token},maxRedirects:0});
        const data = await json(response);
        const result = summary(response,data,key);
        assert.ok(result.passed);assert.ok(data[key].some(row => row[idKey] === id));
        return {...result,fixturePresent:true};
      });
      await check(name+'AnonymousDenied',async () => {
        const response = await anonymousContext.get(url,{params,headers:{...h5Headers,'X-Requested-With':'XMLHttpRequest'},maxRedirects:0});
        const data = await json(response);
        // A server error is not acceptable evidence of a deliberate authorization rejection.
        const denied = [301,302,303,307,308,401,403].includes(response.status()) ||
          (response.status() === 200 && data && data.code !== undefined && Number(data.code) !== 0 && !Array.isArray(data[key]));
        return {http:response.status(),passed:denied};
      });
    }
    await check('ownerOtherRoomDeniedDespiteForgedIdentity',async () => {
      assert.ok(auth?.token);
      const response = await ownerContext.get('http://127.0.0.1:8012/app/fee.listFee',{
        params:{communityId:COMMUNITY,payerObjId:ROOM,payerObjType:'3333',page:1,row:10},
        headers:{...h5Headers,'user-id':'30518940136629616640','user_id':'30518940136629616640',cookie:'_java110_token_='+auth.token},maxRedirects:0});
      return {http:response.status(),passed:response.status() === 403};
    });
    await check('ownerCannotUseManagementApi',async () => {
      assert.ok(auth?.token);
      // Read-only management query: even a regression cannot send a notification.
      const response = await ownerContext.get('http://127.0.0.1:8012/app/fee.listBill',{
        params:{communityId:fixture.communityId,page:1,row:10},
        headers:{...h5Headers,cookie:'_java110_token_='+auth.token},maxRedirects:0});
      return {http:response.status(),passed:response.status() === 403};
    });
    await check('ownerBindingListIgnoresForgedUser',async () => {
      assert.ok(auth?.token);
      const response = await ownerContext.get('http://127.0.0.1:8012/app/owner.listAppUserBindingOwners',{
        params:{userId:'30518940136629616640',page:1,row:100},
        headers:{...h5Headers,cookie:'_java110_token_='+auth.token},maxRedirects:0});
      const body=await json(response);
      assert.equal(response.status(),200);assert.equal(Number(body.code),0);assert.ok(body.data?.length);
      assert.ok(body.data.every(binding => binding.ownerId === fixture.ids.owner));
      return {http:response.status(),scopedToFixture:true};
    });

    let knownBaselineFailuresOnly = false;
    if (config.allowKnownMissingAccountTable || config.allowKnownBaselineGaps) {
      const failures = report.checks.filter(result => !result.passed);
      const contractsServicePassed = report.checks.some(result => result.name === 'contractsService' && result.passed);
      const allowed = failures.length > 0 && failures.every(result =>
        (result.name === 'accounts' && result.http === 500) ||
        (config.allowKnownBaselineGaps && result.name === 'contractsGateway' && result.code === 404 && contractsServicePassed));
      if (allowed) {
        await check('knownBaselineGapProof',async () => {
          const issues = [];
          if (failures.some(result => result.name === 'accounts')) {
            const proof = JSON.parse(execFileSync('python',[path.join(__dirname,'consolidation-community-expected.py'),'--account-table-status'],
              {cwd:root,encoding:'utf8',timeout:TIMEOUT,stdio:['ignore','pipe','pipe'],windowsHide:true}));
            assert.deepEqual(proof,{database:'TT',table:'account',accountTablePresent:false});
            issues.push({name:'accounts',http:500,proof:'TT.account is absent',acceptedForFixtureWrite:true});
          }
          if (failures.some(result => result.name === 'contractsGateway')) {
            assert.ok(contractsServicePassed);
            issues.push({name:'contractsGateway',code:404,proof:'Direct contractsService query passed; existing application gateway route is unavailable',acceptedForFixtureWrite:true});
          }
          knownBaselineFailuresOnly = true;
          report.knownBaselineIssues = issues;
          return {verifiedIssues:issues.length};
        });
      }
    }
    if (config.write) await check('syntheticRoomWriteRestore',async () => {
      assert.ok(report.checks.every(result => result.passed) || knownBaselineFailuresOnly,'Read checks must pass before fixture write');
      report.fixtureWritePhases = [];
      const phase = (name,details = {}) => {report.fixtureWritePhases.push({name,...details});save();};
      const url = `http://127.0.0.1:3000/callComponent/room/listRoom?communityId=${COMMUNITY}&page=1&row=50&roomNum=BIND202`;
      async function room() {
        const response = await context.request.get(url,{timeout:TIMEOUT});
        const data = await json(response);
        assert.equal(response.status(),200);assert.ok(Array.isArray(data?.rooms));
        const matches = data.rooms.filter(row => row.roomNum === 'BIND202');
        assert.equal(matches.length,1);assert.equal(matches[0].roomId,ROOM);return matches[0];
      }
      const original = await room();
      phase('originalRead',{guardedRoomId:true});
      assert.equal(original.remark,REMARK);
      // This legacy list DTO omits communityId. The query already fixes community
      // scope; reject a contradictory field if a newer DTO does supply one.
      if (original.communityId !== undefined && original.communityId !== null) assert.equal(original.communityId,COMMUNITY);
      const keys = ['roomId','unitId','roomNum','layer','section','apartment','builtUpArea','feeCoefficient','state','remark','communityId','floorId','roomType','unitPrice'];
      const originalFields = Object.fromEntries(keys.filter(key => original[key] !== undefined && original[key] !== null).map(key => [key,original[key]]));
      const payload = {...originalFields,communityId:COMMUNITY};
      const snapshotFile = path.join(runtime,`consolidation-room-snapshot-${config.profile}-${config.label}.json`);
      // Only allowlisted synthetic room business fields; no owner/person/account data.
      fs.writeFileSync(snapshotFile,JSON.stringify({roomNum:'BIND202',originalFields,payload},null,2)+'\n');
      phase('snapshotSaved',{fields:Object.keys(originalFields)});
      async function update(data,name) {
        const response = await context.request.post('http://127.0.0.1:3000/callComponent/editRoom/update',{data:{...data,attrs:[]},timeout:TIMEOUT});
        const body = await json(response);
        phase(name,{http:response.status(),code:Number.isFinite(Number(body?.code)) ? Number(body.code) : null});
        return {http:response.status(),body};
      }
      function compare(actual,name) {
        const mismatchedFields = Object.keys(originalFields).filter(key => !Object.is(actual[key],originalFields[key]));
        phase(name,{passed:mismatchedFields.length === 0,mismatchedFields});
        assert.deepEqual(mismatchedFields,[]);
      }
      const marker = `${REMARK} ${config.profile.toUpperCase()} CHECK`;
      let restored = false;
      try {
        const rejected = await update({...payload,builtUpArea:'INVALID_CONSOLIDATION_TEST',remark:marker},'invalidUpdateResponse');
        assert.ok(rejected.http !== 200 || (rejected.body?.code !== undefined && Number(rejected.body.code) !== 0),'Malformed update unexpectedly accepted');
        const rejectedAfter = await room();
        compare(rejectedAfter,'invalidUpdateUnchanged');
        const success = await update({...payload,remark:marker},'validUpdateResponse');
        assert.equal(success.http,200);assert.ok(validCode(success.body));
        const changed = await room();
        phase('validUpdateRead',{passed:changed.remark === marker});
        assert.equal(changed.remark,marker);
      } finally {
        let restore;
        try {restore = await update(payload,'restoreResponse');}
        catch {phase('restoreResponse',{passed:false,error:'Request failed; verifying persisted fixture fields'});}
        // Always read even if the transport/response failed: restoration evidence
        // comes from persisted fields, not from a generic success response.
        const after = await room();
        compare(after,'restoreRead');
        restored = true;
        report.roomRestored = true;save();
      }
      return {guardedFixture:true,rejectedUpdateUnchanged:true,writeRead:true,restored};
    });
  } catch {
    report.checks.push({name:'runtimeSetup',passed:false,error:'Local runtime/auth/fixture setup failed; refresh saved admin login and inspect local logs privately.'});
  } finally {
    if (anonymousContext) await anonymousContext.dispose().catch(() => {});
    if (ownerContext) await ownerContext.dispose().catch(() => {});
    if (browser) await browser.close().catch(() => {});
    report.finishedAt = new Date().toISOString();
    report.passed = report.checks.length > 0 && report.checks.every(result => result.passed);
    save();
    console.log(JSON.stringify({profile:config.profile,label:config.label,passed:report.passed,
      checks:report.checks.length,failed:report.checks.filter(result => !result.passed).map(result => result.name),
      report:path.relative(root,output).replace(/\\/g,'/')}));
    if (!report.passed) process.exitCode = 1;
  }
}

if (require.main === module) {
  try {
    const config = options(process.argv.slice(2));
    if (config.help) console.log('node scripts/tests/consolidation-runtime-smoke.cjs --profile property|merged --label LABEL [--write] [--allow-known-baseline-gaps] [--allow-known-missing-account-table]');
    else main(config).catch(() => {console.error('Runtime smoke failed; no response details emitted.');process.exitCode = 1;});
  } catch {console.error('Invalid arguments. Use --help.');process.exitCode = 1;}
}

module.exports = {options};
