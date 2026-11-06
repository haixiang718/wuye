const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const pagePath = path.join(__dirname, '../public/pages/property/billOweManage/billOweManage');
const bill = (extra = {}) => ({ oweId: 'owe-1', state: '1000', reviewState: 'PUBLISHED', billAmountOwed: '125.50',
    amountOwed: '50.00', ownerName: '张三', payerObjName: '1栋-101', ...extra });

function page(confirmResult = true) {
    const posts = [], gets = [], confirmations = [], toasts = [];
    let nextId = 0;
    const vc = {
        component: {},
        extends(definition) { this.component = { ...definition.data, ...definition.methods }; },
        getCurrentCommunity: () => ({ communityId: 'community-1' }),
        uuid: () => `request-${++nextId}`,
        toast: message => toasts.push(message),
        emit() {},
        http: {
            apiPost(url, body, options, success, failure) { posts.push({ url, body: JSON.parse(body), options, success, failure }); },
            apiGet(url, params, success) { gets.push({ url, params: JSON.parse(JSON.stringify(params)) });
                success(JSON.stringify({ total: 1, records: 1, data: [bill({ state: '3000' })] })); }
        }
    };
    vm.runInNewContext(fs.readFileSync(`${pagePath}.js`, 'utf8'), {
        window: { vc, confirm: message => { confirmations.push(message); return confirmResult; } }
    });
    return { component: vc.component, posts, gets, confirmations, toasts };
}

test('only unpaid bill rows with positive outstanding amounts can be pushed', () => {
    const { component } = page();
    assert.equal(typeof component._canPushBill, 'function', 'bill push eligibility is available');
    assert.equal(component._canPushBill(bill()), true);
    assert.equal(component._canPushBill(bill({ state: 3000 })), true);
    for (const extra of [{ state: '2000' }, { state: 'unknown' }, { billAmountOwed: '0' },
        { billAmountOwed: '-1' }, { billAmountOwed: 'bad' }, { billAmountOwed: Infinity },
        { reviewState: 'DRAFT' }, { reviewState: 'REJECTED' }, { reviewState: undefined }]) {
        assert.equal(component._canPushBill(bill(extra)), false);
    }
});

test('confirmation identifies owner, room and bill amount, and cancel sends nothing', () => {
    const p = page(false);
    assert.equal(typeof p.component._pushBill, 'function', 'manual push action is available');
    p.component._pushBill(bill());
    assert.match(p.confirmations[0], /张三/);
    assert.match(p.confirmations[0], /1栋-101/);
    assert.match(p.confirmations[0], /125\.50/);
    assert.equal(p.posts.length, 0);
});

test('notified bill requires an explicit resend warning', () => {
    const p = page();
    p.component._pushBill(bill({ state: '3000' }));
    assert.match(p.confirmations[0], /再次推送/);
    assert.match(p.confirmations[0], /重复/);
});

test('paid and zero outstanding bills cannot send even when action is called directly', () => {
    const p = page();
    p.component._pushBill(bill({ state: '2000' }));
    p.component._pushBill(bill({ billAmountOwed: 0 }));
    p.component._pushBill(bill({ reviewState: 'DRAFT' }));
    p.component._pushBill(bill({ reviewState: 'REJECTED' }));
    assert.equal(p.posts.length, 0);
    assert.equal(p.confirmations.length, 0);
});

test('sends only authoritative identifiers through authenticated API and blocks duplicate clicks', () => {
    const p = page();
    p.component._pushBill(bill());
    p.component._pushBill(bill());
    assert.equal(p.posts.length, 1);
    assert.equal(p.confirmations.length, 1);
    assert.equal(p.posts[0].url, '/fee.pushBill');
    assert.deepEqual(p.posts[0].body, { communityId: 'community-1', oweId: 'owe-1', requestId: 'request-1' });
    assert.equal(p.posts[0].options.emulateJSON, true);
    assert.equal(p.component._isPushingBill(bill()), true);
});

test('accepted response displays server wording and refreshes the current page', () => {
    const p = page();
    Object.assign(p.component.billOweManageInfo.conditions, { page: 4, row: 10, ownerName: '张三' });
    p.component._pushBill(bill());
    p.posts[0].success(JSON.stringify({ code: 0, msg: '微信已受理账单通知', data: { acceptedCount: 1 } }));
    assert.deepEqual(p.toasts, ['微信已受理账单通知']);
    assert.equal(p.gets.length, 1);
    assert.equal(p.gets[0].params.params.page, 4);
    assert.equal(p.gets[0].params.params.ownerName, '张三');
    assert.equal(p.component._isPushingBill(bill()), false);
    p.component._pushBill(bill({ state: '3000' }));
    assert.equal(p.posts[1].body.requestId, 'request-2');
});

test('definitive error displays server explanation without refreshing', () => {
    const p = page();
    p.component._pushBill(bill());
    p.posts[0].success(JSON.stringify({ code: 400, msg: '业主未绑定公众号', data: {} }));
    assert.deepEqual(p.toasts, ['业主未绑定公众号']);
    assert.equal(p.gets.length, 0);
    assert.equal(p.component._isPushingBill(bill()), false);
});

test('network uncertainty warns to verify receipt and retains request ID for a deliberate retry', () => {
    const p = page();
    p.component._pushBill(bill());
    p.posts[0].failure({}, new Error('timeout'));
    assert.match(p.toasts[0], /不确定/);
    assert.match(p.toasts[0], /核实/);
    assert.match(p.toasts[0], /重试/);
    assert.equal(p.posts.length, 1, 'never automatically retries');
    assert.equal(p.component._isPushingBill(bill()), false);
    p.component._pushBill(bill());
    assert.equal(p.posts[1].body.requestId, p.posts[0].body.requestId);
    assert.match(p.confirmations[1], /核实/);
    assert.equal(p.gets.length, 0);
});

test('partial or uncertain response is not success and retains request ID', () => {
    const p = page();
    p.component._pushBill(bill());
    p.posts[0].success(JSON.stringify({ code: 202, msg: '部分通知已受理，请核实', data: { acceptedCount: 1 } }));
    assert.deepEqual(p.toasts, ['部分通知已受理，请核实']);
    assert.equal(p.gets.length, 0);
    p.component._pushBill(bill());
    assert.equal(p.posts[1].body.requestId, 'request-1');
});

test('invalid response releases sending state and keeps the original request ID', () => {
    const p = page();
    p.component._pushBill(bill());
    p.posts[0].success('<html>gateway error</html>');
    assert.equal(p.component._isPushingBill(bill()), false);
    assert.match(p.toasts[0], /不确定/);
    p.component._pushBill(bill());
    assert.equal(p.posts[1].body.requestId, 'request-1');
});

test('template exposes per-row push action and accurate filter state values', () => {
    const html = fs.readFileSync(`${pagePath}.html`, 'utf8');
    for (const [value, label] of [['1000', '未催缴'], ['3000', '已催缴'], ['2000', '已缴费']]) {
        assert.match(html, new RegExp(`<option value="${value}">${label}</option>`));
    }
    assert.match(html, /v-if="_canPushBill\(billOwe\)"/);
    assert.match(html, /v-on:click="_pushBill\(billOwe\)"/);
    assert.match(html, /:disabled="_isPushingBill\(billOwe\)"/);
    assert.match(html, /再次推送/);
    assert.match(html, /推送账单/);
});
