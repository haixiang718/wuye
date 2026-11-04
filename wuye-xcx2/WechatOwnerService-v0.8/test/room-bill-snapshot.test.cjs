const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const snapshot = {
    billSnapshot: true, feeId: 'fee-1', oweId: 'owe-1', billId: 'bill-1',
    amount: '0.01', feePrice: '0.01', feeEndTime: '2026-10-01', deadlineTime: '2026-11-01',
    endTime: '2026-11-01', feeName: '物业费', feeFlag: '1003006', paymentCycle: 12
};
const navigation = { ...snapshot, roomId: 'room-1', amount: '999.99', builtUpArea: 80, squarePrice: 200, additionalAmount: 10 };
const stripImports = code => code.replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g, '');

function page(getRoomFees = async () => [{ ...snapshot }]) {
    const queries = [], requests = [], toasts = [], discounts = [];
    const context = {
        constant: { mapping: { W_APP_ID: 'appid' }, url: { preOrder: '/payment/preOrder' } },
        onLoad() {}, getCurrentCommunity: () => ({ communityId: 'community-1', communityName: '小区' }),
        getHeaders: () => ({}), request: options => requests.push(options)
    };
    const conditions = [];
    const h5Script = fs.readFileSync(path.join(__dirname, '../pages/roomFee/roomFee.vue'), 'utf8')
        .match(/<script>([\s\S]*?)<\/script>/)[1].split('\n').filter(line => {
            if (line.includes('// #ifdef')) { conditions.push(line.includes('H5')); return false; }
            if (line.includes('// #ifndef')) { conditions.push(!line.includes('H5')); return false; }
            if (line.includes('// #endif')) { conditions.pop(); return false; }
            return conditions.every(Boolean);
        }).join('\n');
    const script = stripImports(h5Script).replace('export default', 'module.exports =');
    const sandbox = {
        module: { exports: {} }, require: name => name.includes('WexinPayFactory')
            ? { resumePending: async () => ({ state: 'NONE' }), preparePayment: async () => ({ state: 'NONE' }) } : context, vcDiscount: {},
        navigator: { userAgent: 'MicroMessenger' },
        uni: { getAccountInfoSync: () => ({ miniProgram: { appId: 'test' } }), getStorageSync: () => '', showToast: data => toasts.push(data) },
        wx: { showLoading() {}, hideLoading() {} },
        getRoomFees: async (params, room) => { queries.push({ params, room }); return getRoomFees(params, room); },
        addMonth: date => date, formatDate: () => 'legacy-date', date2String: value => value
    };
    vm.runInNewContext(script, sandbox);
    const definition = sandbox.module.exports;
    const component = { ...definition.data(), ...definition.methods, paymentEnabled: false,
        $nextTick: callback => callback(), $refs: { vcDiscountRef: { _loadFeeDiscount: (...args) => discounts.push(args) } } };
    return { component, queries, requests, toasts, discounts,
        load: (fee = navigation) => definition.onLoad.call(component, { fee: JSON.stringify(fee) }) };
}

test('fee API preserves each published snapshot identity, amount and period for navigation', async () => {
    const script = stripImports(fs.readFileSync(path.join(__dirname, '../api/fee/feeApi.js'), 'utf8'))
        .replace(/export function/g, 'function') + '\nmodule.exports = { getRoomFees };';
    const room = { roomId: 'room-1', builtUpArea: 80 };
    const sandbox = { module: { exports: {} }, url: {}, dateTimeStringToDateString: value => value,
        request: options => options.success({ statusCode: 200, data: { fees: [{ ...snapshot, feePrice: '0.01' }] } }) };
    vm.runInNewContext(script, sandbox);
    const rows = await sandbox.module.exports.getRoomFees({}, room);
    assert.equal(rows[0].billSnapshot, true);
    assert.equal(rows[0].oweId, 'owe-1');
    assert.equal(rows[0].billId, 'bill-1');
    assert.equal(rows[0].feeEndTime, '2026-10-01');
    assert.equal(rows[0].deadlineTime, '2026-11-01');
    assert.equal(rows[0].amount, '0.01');
    assert.equal(room.oweId, undefined);
});

test('snapshot details requery the exact bill and show server amount and period', async () => {
    const p = page();
    await p.load();
    assert.equal(p.queries.length, 1);
    assert.deepEqual({ ...p.queries[0].params }, {
        page: 1, row: 1, communityId: 'community-1', payerObjId: 'room-1', payerObjType: '3333',
        feeId: 'fee-1', oweId: 'owe-1', billId: 'bill-1'
    });
    assert.equal(p.component.billReady, true);
    assert.equal(p.component.amount, '0.01');
    assert.equal(p.component.receivableAmount, '0.01');
    assert.equal(p.component.feeEndTime, '2026-10-01');
    assert.equal(p.component.endTime, '2026-11-01');
    assert.equal(p.component.feeMonthList.length, 0);
    assert.equal(p.discounts.length, 0);
});

test('snapshot totals and dates cannot be changed through legacy period or discount methods', async () => {
    const p = page();
    await p.load();
    p.component.dateChange({ detail: { value: 0 } });
    p.component.computeFeeDiscount('100.00');
    assert.equal(p.component.receivableAmount, '0.01');
    assert.equal(p.component.endTime, '2026-11-01');
    assert.equal(p.discounts.length, 0);
});

test('snapshot query must finish before enabled payment can submit an order', async () => {
    let resolve;
    const p = page(() => new Promise(done => { resolve = done; }));
    const loading = p.load();
    p.component.paymentEnabled = true;
    p.component.onPayFee();
    await Promise.resolve();
    assert.equal(p.component.billReady, false);
    assert.equal(p.component.receivableAmount, '0.00');
    assert.equal(p.requests.length, 0);
    resolve([{ ...snapshot }]);
    await loading;
    assert.equal(p.component.billReady, true);
});

for (const [name, result] of [
    ['paid or unpublished bill', async () => []],
    ['different bill identity', async () => [{ ...snapshot, oweId: 'another-owe' }]],
    ['non-snapshot data', async () => [{ ...snapshot, billSnapshot: false }]],
    ['invalid amount', async () => [{ ...snapshot, amount: 'NaN' }]],
    ['failed query', async () => { throw new Error('offline'); }]
]) test(name + ' clears navigation amounts and blocks payment', async () => {
    const p = page(result);
    await p.load();
    p.component.paymentEnabled = true;
    p.component.onPayFee();
    await Promise.resolve();
    p.component._payWxApp();
    assert.equal(p.component.billReady, false);
    assert.equal(p.component.receivableAmount, '0.00');
    assert.equal(p.requests.length, 0);
    assert.match(p.toasts[0].title, /账单.*(刷新|重试)/);
});

test('missing snapshot identity cannot become a broad bill query', async () => {
    const p = page();
    await p.load({ ...navigation, oweId: '' });
    assert.equal(p.queries.length, 0);
    assert.equal(p.component.billReady, false);
    assert.match(p.toasts[0].title, /账单/);
});

test('both payment entries stay closed by default and include bill identities only after verification', async () => {
    const p = page();
    await p.load();
    p.component.onPayFee();
    await Promise.resolve();
    p.component._payWxApp();
    assert.equal(p.requests.length, 0);
    assert.match(p.toasts[0].title, /未开通/);
    p.component.paymentEnabled = true;
    p.component.onPayFee();
    await Promise.resolve();
    p.component._payWxApp();
    assert.equal(p.requests.length, 2);
    for (const request of p.requests) {
        assert.equal(request.data.oweId, 'owe-1');
        assert.equal(request.data.billId, 'bill-1');
        assert.equal(request.data.receivedAmount, '0.01');
    }
});

test('ordinary legacy fee details preserve existing calculation and discount loading', async () => {
    const p = page();
    await p.load({ feeId: 'legacy-fee', roomId: 'room-1', feeFlag: '1003006', amount: '1',
        builtUpArea: 80, squarePrice: 1.25, additionalAmount: '5', paymentCycle: 1, endTime: '2026-10-01' });
    assert.equal(p.component.receivableAmount, '105.00');
    assert.equal(p.discounts.length, 1);
    assert.equal(p.queries.length, 0);
    p.component.onPayFee();
    await Promise.resolve();
    assert.equal(p.requests.length, 0);
});
