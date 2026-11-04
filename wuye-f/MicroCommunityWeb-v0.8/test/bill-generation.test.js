const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const pagePath = path.join(__dirname, '../public/pages/property/billManage/billManage');
const config = (extra = {}) => ({ configId: 'config-005', feeName: '测试费用', billType: '005',
    billTypeName: '一次性费用按月出账', ...extra });

function page() {
    const posts = [], configGets = [], billGets = [], toasts = [];
    let communityId = 'community-1';
    const vc = {
        component: {},
        extends(definition) { this.component = { ...definition.data, ...definition.methods }; },
        getCurrentCommunity: () => ({ communityId }),
        toast: message => toasts.push(message),
        emit() {},
        http: {
            get(component, action, params, success, failure) {
                configGets.push({ component, action, params: JSON.parse(JSON.stringify(params)), success, failure });
            },
            apiPost(url, body, options, success, failure) {
                posts.push({ url, body: JSON.parse(body), options, success, failure });
            },
            apiGet(url, params, success) {
                billGets.push({ url, params: JSON.parse(JSON.stringify(params)) });
                success(JSON.stringify({ total: 0, records: 0, data: [] }));
            }
        }
    };
    vm.runInNewContext(fs.readFileSync(`${pagePath}.js`, 'utf8'), { window: { vc, confirm: () => true } });
    return { component: vc.component, posts, configGets, billGets, toasts,
        changeCommunity: value => { communityId = value; } };
}

function ready() {
    const p = page();
    p.component._openBillGeneration();
    p.configGets[0].success(JSON.stringify({ total: 1, records: 1, feeConfigs: [config()] }));
    p.component.billManageInfo.generation.configId = 'config-005';
    return p;
}

test('opens current-community fee configuration selection with visible pagination', () => {
    const p = page();
    assert.equal(typeof p.component._openBillGeneration, 'function', 'manual draft generation is available');
    p.component._openBillGeneration();
    assert.equal(p.component.billManageInfo.generation.show, true);
    assert.equal(p.component.billManageInfo.generation.loading, true);
    assert.equal(p.configGets[0].component, 'feeConfigManage');
    assert.equal(p.configGets[0].action, 'list');
    assert.deepEqual(p.configGets[0].params.params,
        { communityId: 'community-1', feeName: '', billType: '', page: 1, row: 10 });
    p.configGets[0].success(JSON.stringify({ total: 12, records: 2, feeConfigs: [config()] }));
    assert.equal(p.component.billManageInfo.generation.pages, 2);
    p.component._loadBillFeeConfigs(2);
    assert.equal(p.configGets[1].params.params.page, 2, 'recently added configs remain reachable');
    assert.equal(p.component.billManageInfo.generation.configId, '');
});

test('fee name and bill type searches go to the server and reset selection', () => {
    const p = ready();
    Object.assign(p.component.billManageInfo.generation, { feeName: ' 测试费用 ', billType: '005' });
    p.component._loadBillFeeConfigs(1);
    assert.equal(p.configGets[1].params.params.feeName, '测试费用');
    assert.equal(p.configGets[1].params.params.billType, '005');
    assert.equal(p.component.billManageInfo.generation.configId, '');
});

test('selection exposes only supported bill types and rejects stale or foreign selection', () => {
    const p = page();
    p.component._openBillGeneration();
    p.configGets[0].success(JSON.stringify({ total: 7, records: 1, feeConfigs:
        ['001', '002', '003', '004', '005', '006'].map(type => config({ configId: type, billType: type }))
            .concat(config({ configId: 'foreign', communityId: 'other-community' })) }));
    assert.deepEqual(Array.from(p.component.billManageInfo.generation.configs, item => item.billType),
        ['001', '002', '003', '005']);
    for (const configId of ['', '004', 'foreign', 'missing']) {
        p.component.billManageInfo.generation.configId = configId;
        p.component._generateBill();
    }
    assert.equal(p.posts.length, 0);
});

test('generates type 005 using only authoritative IDs and blocks repeated clicks', () => {
    const p = ready();
    p.component._generateBill();
    p.component._generateBill();
    p.component._loadBillFeeConfigs(2);
    assert.equal(p.posts.length, 1);
    assert.equal(p.configGets.length, 1, 'selection cannot change during generation');
    assert.equal(p.posts[0].url, '/fee.generateBill');
    assert.deepEqual(p.posts[0].body, { communityId: 'community-1', configId: 'config-005' });
    assert.equal(p.posts[0].options.emulateJSON, true);
    assert.equal(p.component.billManageInfo.generation.generating, true);
});

test('community change requires a fresh configuration selection before generation', () => {
    const p = ready();
    p.changeCommunity('community-2');
    p.component._generateBill();
    assert.equal(p.posts.length, 0);
    assert.match(p.toasts[0], /小区.*重新/);
});

test('draft success identifies the bill and refreshes current bill page and filters', () => {
    const p = ready();
    Object.assign(p.component.billManageInfo.conditions, { page: 3, row: 10, billName: '原条件' });
    p.component._generateBill();
    p.posts[0].success(JSON.stringify({ code: 0, msg: '已生成待审核账单',
        data: { billId: 'bill-1', reviewState: 'DRAFT', existing: false, receivable: '50.00' } }));
    assert.equal(p.component.billManageInfo.generation.generating, false);
    assert.match(p.toasts[0], /已生成待审核账单/);
    assert.match(p.toasts[0], /bill-1/);
    assert.match(p.component.billManageInfo.generation.result, /bill-1/);
    assert.equal(p.billGets.length, 1);
    assert.equal(p.billGets[0].params.params.page, 3);
    assert.equal(p.billGets[0].params.params.billName, '原条件');
    assert.equal(p.posts.length, 1, 'generation never reviews or pushes');
});

test('existing published bill is refreshed and described accurately without changing it', () => {
    const p = ready();
    p.component._generateBill();
    p.posts[0].success(JSON.stringify({ code: 0,
        data: { billId: 'published-1', reviewState: 'PUBLISHED', existing: true } }));
    assert.match(p.toasts[0], /已存在/);
    assert.match(p.toasts[0], /已审核发布/);
    assert.match(p.toasts[0], /published-1/);
    assert.equal(p.billGets.length, 1);
    assert.equal(p.posts.length, 1);
});

test('server rejection shows actionable reason, restores controls and permits a deliberate retry', () => {
    const p = ready();
    p.component._generateBill();
    p.posts[0].success(JSON.stringify({ code: 400, msg: '无可出账欠费明细' }));
    assert.deepEqual(p.toasts, ['无可出账欠费明细']);
    assert.equal(p.component.billManageInfo.generation.generating, false);
    assert.equal(p.billGets.length, 0);
    p.component._generateBill();
    assert.equal(p.posts.length, 2);
});

test('network or malformed response restores controls and asks to verify the result', () => {
    for (const fail of [request => request.failure({}, new Error('timeout')),
        request => request.success('<html>gateway error</html>'),
        request => request.success('null'),
        request => request.success('{"code":null}'),
        request => request.success('{"code":""}')]) {
        const p = ready();
        p.component._generateBill();
        fail(p.posts[0]);
        assert.equal(p.component.billManageInfo.generation.generating, false);
        assert.match(p.toasts[0], /核对/);
        assert.equal(p.posts.length, 1, 'never retries automatically');
        p.component._generateBill();
        assert.equal(p.posts.length, 2);
    }
});

test('configuration list failure clears loading and allows another search', () => {
    for (const fail of [request => request.failure(), request => request.success('not JSON'),
        request => request.success(JSON.stringify({ code: 403, msg: '无收费项查看权限' }))]) {
        const p = page();
        p.component._openBillGeneration();
        fail(p.configGets[0]);
        assert.equal(p.component.billManageInfo.generation.loading, false);
        assert.equal(p.component.billManageInfo.generation.configs.length, 0);
        assert.ok(p.toasts[0]);
        p.component._loadBillFeeConfigs(1);
        assert.equal(p.configGets.length, 2);
    }
});

test('template explains batch drafts, exposes searchable paged selection, and preserves review', () => {
    const html = fs.readFileSync(`${pagePath}.html`, 'utf8');
    assert.match(html, /生成待审核账单/);
    assert.match(html, /v-on:click="_openBillGeneration\(\)"/);
    assert.match(html, /v-model="billManageInfo.generation.feeName"/);
    assert.match(html, /v-model="billManageInfo.generation.billType"/);
    assert.match(html, /v-model="billManageInfo.generation.configId"/);
    assert.match(html, /feeConfig\.feeName/);
    assert.match(html, /feeConfig\.billTypeName/);
    assert.match(html, /上一页/);
    assert.match(html, /下一页/);
    assert.match(html, /按收费项目整批/);
    assert.match(html, /核对后点击“审核并推送”/);
    assert.match(html, /v-on:click="_generateBill\(\)"/);
    assert.match(html, /:disabled="[^"]*generation\.generating/);
    assert.match(html, /v-on:click="_reviewBill\(bill\)"/);
});
