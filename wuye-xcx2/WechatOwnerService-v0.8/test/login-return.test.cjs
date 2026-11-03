const test = require('node:test');
const assert = require('node:assert/strict');

function runtime() {
  const storage = new Map();
  return {getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key)};
}

test('a bill destination is restored once after login without retaining OAuth credentials', () => {
  const target = require('../auth/return-target.js'), store = runtime();
  target.remember('#/pages/roomFeeListNew/roomFeeListNew?roomId=room-2&authTicket=secret&code=secret', store);
  assert.equal(target.consume(store), '/pages/roomFeeListNew/roomFeeListNew?roomId=room-2');
  assert.equal(target.consume(store), '');
});

test('external, unknown, malformed and stale destinations cannot redirect the owner', () => {
  const target = require('../auth/return-target.js');
  for (const route of ['https://other.example','//other.example','#/pages/repair/repair','#/pages/login/login','#/pages/roomFeeListNew/roomFeeListNew?roomId=%broken']) {
    const store = runtime();target.remember(route,store);assert.equal(target.consume(store),'',route);
  }
  const store = runtime();
  store.setStorageSync('owner_return_target',{route:'/pages/roomOweFee/roomOweFee?roomId=room-1',createdAt:Date.now()-31*60*1000});
  assert.equal(target.consume(store), '');
});
