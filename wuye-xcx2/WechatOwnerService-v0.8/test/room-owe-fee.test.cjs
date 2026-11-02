const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(overrides = {}) {
    const calls = [], toasts = [], requests = [];
    const script = fs.readFileSync(path.join(__dirname, '../pages/roomOweFee/roomOweFee.vue'), 'utf8')
        .match(/<script>([\s\S]*?)<\/script>/)[1]
        .replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
        .replace('export default', 'module.exports =');
    const context = { constant: { mapping: { W_APP_ID: 'appid' }, url: {} }, onLoad() {}, getHeaders: () => ({}), request: data => requests.push(data) };
    const sandbox = {
        module: { exports: {} }, require: () => context,
        uni: { getAccountInfoSync: () => ({ miniProgram: { appId: 'test' } }), getStorageSync: () => '', showToast: data => toasts.push(data) },
        wx: { showLoading() {}, hideLoading() {} },
        getCurCommunity: async () => ({ communityId: 'community-1', communityName: '小区' }),
        getRooms: async () => ({ rooms: [
            { roomId: 'room-1', floorNum: '1', unitNum: '1', roomNum: '101', builtUpArea: '80' },
            { roomId: 'room-2', floorNum: '2', unitNum: '3', roomNum: '502', builtUpArea: '98.50' }
        ] }),
        getRoomOweFees: async params => { calls.push(params); return [{ feePrice: '0.10' }, { feePrice: '0.20' }, { feePrice: 1.01 }]; },
        ...overrides
    };
    vm.runInNewContext(script, sandbox);
    const definition = sandbox.module.exports;
    const component = { ...definition.data(), ...definition.methods, paymentEnabled: false };
    return { component, calls, toasts, requests,
        load: options => definition.onLoad.call(component, options),
        settle: () => new Promise(setImmediate) };
}

test('initialization loads only the requested owned room once with its full room details', async () => {
    const p = page();
    await p.load({ roomId: 'room-2' });
    await p.settle();
    assert.equal(p.component.curRoom.roomId, 'room-2');
    assert.equal(p.component.builtUpArea, '98.50');
    assert.equal(p.component.floorNum, '2');
    assert.equal(p.component.unitNum, '3');
    assert.equal(p.component.roomNum, '502');
    assert.equal(p.calls.length, 1);
    assert.equal(p.calls[0].payObjId, 'room-2');
    assert.equal(p.calls[0].communityId, 'community-1');
    assert.equal(p.component.receivableAmount, '1.31');
});

test('reloading recomputes numeric and string amounts in cents without carrying the previous total', async () => {
    const p = page();
    p.component.roomId = 'room-2';
    p.component.communityId = 'community-1';
    await p.component._loadRoomOweFee();
    await p.settle();
    await p.component._loadRoomOweFee();
    await p.settle();
    assert.equal(p.component.receivableAmount, '1.31');
    assert.equal(p.calls.length, 2);
});

test('missing requested room does not query another room or leave stale fees', async () => {
    const p = page();
    await p.load({ roomId: 'foreign-room' });
    await p.settle();
    assert.equal(p.calls.length, 0);
    assert.equal(p.component.fees.length, 0);
    assert.equal(p.component.receivableAmount, '0.00');
    assert.match(p.toasts[0].title, /房屋/);
});

test('ordinary navigation selects the first room and empty rooms do not request fees', async () => {
    const p = page();
    await p.load({});
    await p.settle();
    assert.equal(p.component.curRoom.roomId, 'room-1');
    assert.equal(p.calls[0].payObjId, 'room-1');
    const empty = page({ getRooms: async () => ({ rooms: [] }) });
    await empty.load({});
    await empty.settle();
    assert.equal(empty.calls.length, 0);
    assert.equal(empty.component.fees.length, 0);
});

for (const [name, getRoomOweFees] of [
    ['empty results', async () => []],
    ['missing results', async () => undefined],
    ['fee request rejection', async () => { throw new Error('offline'); }]
]) test(name + ' clear old fees and total without a second promise failure', async () => {
    const p = page({ getRoomOweFees });
    p.component.roomId = 'room-2';
    p.component.fees = [{ feePrice: '99.00' }];
    p.component.receivableAmount = '99.00';
    await p.component._loadRoomOweFee();
    await p.settle();
    assert.equal(p.component.fees.length, 0);
    assert.equal(p.component.receivableAmount, '0.00');
});

test('initial room loading failure is handled without requesting fees', async () => {
    const p = page({ getRooms: async () => { throw new Error('offline'); } });
    await p.load({ roomId: 'room-2' });
    await p.settle();
    assert.equal(p.calls.length, 0);
    assert.equal(p.component.fees.length, 0);
    assert.equal(p.component.receivableAmount, '0.00');
    assert.match(p.toasts[0].title, /加载失败/);
});

test('direct payment methods respect the disabled payment switch', () => {
    const p = page();
    p.component.onPayFee();
    p.component._payWxApp();
    assert.equal(p.requests.length, 0);
    assert.equal(p.toasts.length, 2);
    assert.match(p.toasts[0].title, /未开通/);
});
