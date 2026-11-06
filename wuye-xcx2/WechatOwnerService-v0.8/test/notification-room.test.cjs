const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(rooms = [{ roomId: 'room-1' }, { roomId: 'room-2' }], operations = {}) {
    const calls = [], toasts = [], loads = [];
    const file = path.join(__dirname, '../pages/roomFeeListNew/roomFeeListNew.vue');
    const script = fs.readFileSync(file, 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
        .replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
        .replace('export default', 'module.exports =');
    const sandbox = {
        module: { exports: {} }, noDataPage: {}, feeDisplay: require('../utils/fee-display'),
        require: () => ({ constant: {}, onLoad: options => loads.push(options) }),
        uni: { showToast: options => toasts.push(options) },
        getRooms: operations.getRooms || (async () => ({ rooms, owner: { communityId: 'community-1', communityName: '小区' } })),
        getRoomFees: async (params, room) => { calls.push({ params, room }); return operations.getRoomFees ? operations.getRoomFees(params, room) : [{ feeName: room.roomId }]; }
    };
    vm.runInNewContext(script, sandbox);
    const definition = sandbox.module.exports;
    const component = { ...definition.data(), ...definition.methods };
    return { component, calls, toasts, loads,
        load: options => definition.onLoad.call(component, options),
        show: async () => { definition.onShow.call(component); await new Promise(setImmediate); } };
}

test('notification roomId selects the matching owned room instead of the first room', async () => {
    const p = page();
    p.load({ roomId: 'room-2', authTicket: 'ticket' });
    await p.show();
    assert.equal(p.component.curRoom.roomId, 'room-2');
    assert.equal(p.calls.length, 1);
    assert.equal(p.calls[0].params.payerObjId, 'room-2');
    assert.equal(p.calls[0].params.communityId, 'community-1');
    assert.equal(p.loads[0].authTicket, 'ticket');
});

test('missing notification room never queries fees for another owned room', async () => {
    const p = page();
    p.load({ roomId: 'not-owned' });
    await p.show();
    assert.equal(p.calls.length, 0);
    assert.equal(p.component.curRoom.roomId, undefined);
    assert.equal(p.component.noData, true);
    assert.equal(p.component.moreRooms.length, 0);
    assert.match(p.toasts[0].title, /房屋/);
});

test('notification room with no owned rooms reports no data and a helpful message', async () => {
    const p = page([]);
    p.load({ roomId: 'room-2' });
    await p.show();
    assert.equal(p.calls.length, 0);
    assert.equal(p.component.noData, true);
    assert.equal(p.toasts.length, 1);
});

test('ordinary navigation keeps the first room default', async () => {
    const p = page();
    p.load({});
    await p.show();
    assert.equal(p.component.curRoom.roomId, 'room-1');
    assert.equal(p.calls[0].params.payerObjId, 'room-1');
    assert.equal(p.toasts.length, 0);
});

test('manual room switch overrides notification selection and survives a page revisit', async () => {
    const p = page();
    p.load({ roomId: 'room-1' });
    await p.show();
    p.component.switchRoom(p.component.rooms[1]);
    await new Promise(setImmediate);
    assert.equal(p.calls.at(-1).params.payerObjId, 'room-2');
    await p.show();
    assert.equal(p.component.curRoom.roomId, 'room-2');
    assert.equal(p.calls.at(-1).params.payerObjId, 'room-2');
});

test('ordinary navigation without rooms keeps existing no-data behavior', async () => {
    const p = page([]);
    p.load({});
    await p.show();
    assert.equal(p.component.noData, true);
    assert.equal(p.calls.length, 0);
    assert.equal(p.toasts.length, 0);
});

test('slow response for a previous room cannot overwrite the current room fees', async () => {
    const pending = new Map();
    const p = page(undefined, { getRoomFees: (params, room) => new Promise(resolve => pending.set(room.roomId, resolve)) });
    p.load({});
    await p.show();
    p.component.switchRoom(p.component.rooms[1]);
    pending.get('room-2')([{ feeName: 'room-2-current' }]);
    await new Promise(setImmediate);
    pending.get('room-1')([{ feeName: 'room-1-stale' }]);
    await new Promise(setImmediate);
    assert.equal(p.component.curRoom.roomId, 'room-2');
    assert.equal(p.component.moreRooms[0].feeName, 'room-2-current');
});

test('empty fees show the no-data state instead of a blank fee list', async () => {
    const p = page(undefined, { getRoomFees: async () => [] });
    p.load({});
    await p.show();
    assert.equal(p.component.noData, true);
});

test('a failed old room request cannot replace the current room with an error state', async () => {
    let rejectOld;
    const p=page(undefined,{getRoomFees:(params,room)=>room.roomId==='room-1'
        ? new Promise((resolve,reject)=>{rejectOld=reject;}) : Promise.resolve([{feeName:'room-2-current'}])});
    p.load({});await p.show();
    p.component.switchRoom(p.component.rooms[1]);await new Promise(setImmediate);
    rejectOld(new Error('old request failed'));await new Promise(setImmediate);
    assert.equal(p.component.noData,false);
    assert.equal(p.component.moreRooms[0].feeName,'room-2-current');
    assert.equal(p.toasts.length,0);
});

test('room loading failure clears old fees and shows a retry message', async () => {
    const p = page(undefined, { getRooms: async () => { throw new Error('offline'); } });
    p.component.moreRooms = [{ feeName: 'old fees' }];
    p.load({});
    await p.show();
    assert.equal(p.component.moreRooms.length, 0);
    assert.equal(p.component.noData, true);
    assert.match(p.toasts[0].title, /加载失败|重试/);
});
