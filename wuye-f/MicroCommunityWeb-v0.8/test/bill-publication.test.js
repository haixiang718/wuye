const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const pagePath = path.join(__dirname, '../public/pages/property/billManage/billManage');
const bill = (extra = {}) => ({ billId: 'bill-1', reviewState: 'DRAFT', curBill: 'T', ...extra });
const notification = (extra = {}) => ({ state: 'QUEUED', total: 3, processed: 0, accepted: 0,
    failed: 0, uncertain: 0, skipped: 0, errors: [], canRetry: false, ...extra });
const result = (extra = {}, top = {}) => JSON.stringify({ code: 0, msg: '已审核，通知已排队',
    data: { billId: 'bill-1', reviewState: 'PUBLISHED', notification: notification(extra) }, ...top });

function page() {
    const posts = [], gets = [], toasts = [], confirmations = [], timers = new Map();
    let definition, component, communityId = 'community-1', timerId = 0, confirm = true;
    const vc = {
        extends(value) { definition = value; component = this.component = { ...value.data, ...value.methods }; },
        getCurrentCommunity: () => communityId ? ({ communityId }) : null,
        toast: value => toasts.push(value), emit() {},
        http: {
            apiPost(url, body, options, success, failure) {
                posts.push({ url, body: JSON.parse(body), options, success, failure });
            },
            apiGet(url, params, success, failure) {
                gets.push({ url, params: JSON.parse(JSON.stringify(params)), success, failure });
            }
        }
    };
    const window = { vc, confirm(value) { confirmations.push(value); return confirm; },
        setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
        clearTimeout(id) { timers.delete(id); } };
    vm.runInNewContext(fs.readFileSync(`${pagePath}.js`, 'utf8'), { window });
    component.billManageInfo.bills = [bill()];
    return { vc, component, posts, gets, toasts, confirmations, timers,
        confirm: value => { confirm = value; },
        community: value => { communityId = value; },
        destroy() { definition._destroyedMethod(); },
        tick() { const [id, timer] = [...timers][0] || []; assert.ok(timer, 'expected pending poll');
            timers.delete(id); timer.callback(); },
        respondList(rows, extra = {}) { gets.findLast(item => item.url === '/fee.listBill').success(
            JSON.stringify({ total: rows.length, records: 1, data: rows, ...extra })); }
    };
}

test('one confirmed action requests publication and notification with trusted IDs and prevents duplicate clicks', () => {
    const p = page();
    p.component._reviewBill(p.component.billManageInfo.bills[0]);
    p.component._reviewBill(p.component.billManageInfo.bills[0]);
    assert.equal(p.posts.length, 1);
    assert.equal(p.posts[0].url, '/fee.reviewBill');
    assert.deepEqual(p.posts[0].body, { communityId: 'community-1', billId: 'bill-1',
        remark: '物业审核并推送', notify: true });
    assert.match(p.confirmations[0], /公众号/);
    assert.match(p.confirmations[0], /H5/);
});

test('canceling confirmation performs no review or notification request', () => {
    const p = page(); p.confirm(false);
    p.component._reviewBill(p.component.billManageInfo.bills[0]);
    assert.equal(p.posts.length, 0);
});

test('published result starts read-only polling and keeps current list page and filters', () => {
    const p = page();
    Object.assign(p.component.billManageInfo.conditions, { page: 3, row: 20, billName: '物业费' });
    p.component._reviewBill(p.component.billManageInfo.bills[0]); p.posts[0].success(result());
    assert.equal(p.component.billManageInfo.bills[0].reviewState, 'PUBLISHED');
    assert.equal(p.component.billManageInfo.notifications['bill-1'].state, 'QUEUED');
    assert.equal(p.component.billManageInfo.reviewing['bill-1'], undefined);
    assert.equal(p.gets.findLast(item => item.url === '/fee.listBill').params.params.page, 3);
    assert.equal(p.gets.findLast(item => item.url === '/fee.listBill').params.params.row, 20);
    assert.equal(p.timers.size, 1);
    p.tick();
    const status = p.gets.findLast(item => item.url === '/fee.billNotificationStatus');
    assert.equal(status.url[0], '/', 'status reads must use authenticated /app forwarding');
    assert.deepEqual(status.params.params, { communityId: 'community-1', billId: 'bill-1' });
    assert.equal(p.posts.length, 1, 'polling must never send a message');
});

test('status loading cannot overlap and completion stops polling with accurate accepted counts', () => {
    const p = page(); p.component.billManageInfo.bills = [bill({ reviewState: 'PUBLISHED' })];
    p.component._refreshBillNotification(p.component.billManageInfo.bills[0], true);
    p.component._refreshBillNotification(p.component.billManageInfo.bills[0], true);
    assert.equal(p.gets.length, 1);
    p.gets[0].success(result({ state: 'RUNNING', processed: 1, accepted: 1 }));
    assert.equal(p.timers.size, 1); p.tick();
    const pending = p.gets.at(-1); p.tick();
    assert.equal(p.gets.at(-1), pending, 'slow status request should not overlap');
    pending.success(result({ state: 'COMPLETED', processed: 3, accepted: 2, skipped: 1 }));
    assert.equal(p.timers.size, 0);
    const summary = p.component._billNotificationSummary('bill-1');
    assert.match(summary, /微信受理.*2/); assert.match(summary, /跳过.*1/);
    assert.doesNotMatch(summary, /已收到|送达/);
});

test('published enqueue failure remains visible and explicit retry sends the retry flag once', () => {
    const p = page(); p.component._reviewBill(p.component.billManageInfo.bills[0]);
    p.posts[0].success(result({ state: 'FAILED', failed: 1, canRetry: true,
        errors: [{ oweId: 'owe-1', msg: '通知任务未创建，请重试', retryable: true }] },
        { code: 500, msg: '已审核发布，通知任务未创建' }));
    assert.equal(p.component.billManageInfo.bills[0].reviewState, 'PUBLISHED');
    assert.equal(p.component.billManageInfo.notifications['bill-1'].canRetry, true);
    p.component._retryBillNotifications(p.component.billManageInfo.bills[0]);
    p.component._retryBillNotifications(p.component.billManageInfo.bills[0]);
    assert.equal(p.posts.length, 2);
    assert.equal(p.posts[1].body.notify, true); assert.equal(p.posts[1].body.retryNotifications, true);
    assert.match(p.confirmations.at(-1), /失败/);
});

test('nonretryable uncertain tasks and drafts cannot trigger a retry', () => {
    const p = page();
    p.component.billManageInfo.notifications['bill-1'] = notification({ state: 'COMPLETED', uncertain: 1 });
    p.component._retryBillNotifications(p.component.billManageInfo.bills[0]);
    p.component.billManageInfo.bills[0].reviewState = 'PUBLISHED';
    p.component._retryBillNotifications(p.component.billManageInfo.bills[0]);
    assert.equal(p.posts.length, 0);
});

test('loading a page reads status for its published bills without sending or polling NONE', () => {
    const p = page(); p.component._listbills(2, 10);
    p.respondList([bill({ reviewState: 'PUBLISHED' }), bill({ billId: 'draft' })]);
    const statuses = p.gets.filter(item => item.url === '/fee.billNotificationStatus');
    assert.equal(statuses.length, 1);
    statuses[0].success(result({ state: 'NONE', total: 0 }));
    assert.equal(p.posts.length, 0); assert.equal(p.timers.size, 0);
});

test('page destruction clears poll and ignores late status response', () => {
    const p = page(); p.component.billManageInfo.bills[0].reviewState = 'PUBLISHED';
    p.component._refreshBillNotification(p.component.billManageInfo.bills[0], true);
    p.gets[0].success(result()); p.tick(); const late = p.gets.at(-1);
    p.destroy(); assert.equal(p.timers.size, 0);
    late.success(result({ state: 'COMPLETED', accepted: 3, processed: 3 }));
    assert.equal(p.component.billManageInfo.notifications['bill-1'].state, 'QUEUED');
    assert.equal(p.timers.size, 0);
});

test('changing community stops polling and ignores stale response before it can update the page', () => {
    const p = page(); p.component.billManageInfo.bills[0].reviewState = 'PUBLISHED';
    p.component._refreshBillNotification(p.component.billManageInfo.bills[0], true);
    p.gets[0].success(result()); p.tick(); const late = p.gets.at(-1);
    p.community('community-2'); late.success(result({ state: 'COMPLETED', accepted: 3 }));
    p.tick(); assert.equal(p.timers.size, 0);
    assert.equal(p.component.billManageInfo.notifications['bill-1'].state, 'QUEUED');
});

test('pagination only polls current visible bills and drops stale result from old page', () => {
    const p = page(); p.component.billManageInfo.bills[0].reviewState = 'PUBLISHED';
    p.component._refreshBillNotification(p.component.billManageInfo.bills[0], true);
    p.gets[0].success(result());
    p.component._listbills(2, 10); p.respondList([bill({ billId: 'other', reviewState: 'DRAFT' })]);
    assert.equal(p.timers.size, 0);
});

test('network and malformed review responses release controls without claiming notification success', () => {
    for (const mode of ['network', 'malformed']) {
        const p = page(); p.component._reviewBill(p.component.billManageInfo.bills[0]);
        mode === 'network' ? p.posts[0].failure() : p.posts[0].success('{');
        assert.equal(p.component.billManageInfo.reviewing['bill-1'], undefined);
        assert.match(p.toasts.at(-1), /未知|核对/);
        assert.equal(p.component.billManageInfo.bills[0].reviewState, 'DRAFT');
        assert.equal(p.timers.size, 0);
    }
});

test('malformed status response preserves last known job and reports an actionable read error', () => {
    const p = page(); p.component.billManageInfo.bills[0].reviewState = 'PUBLISHED';
    p.component._refreshBillNotification(p.component.billManageInfo.bills[0], true);
    p.gets[0].success(result({ state: 'RUNNING' })); p.tick(); p.gets.at(-1).success('{}');
    assert.equal(p.component.billManageInfo.notifications['bill-1'].state, 'RUNNING');
    assert.match(p.component.billManageInfo.notificationErrors['bill-1'], /刷新|查询/);
    assert.equal(p.posts.length, 0);
});

test('template presents combined action, notification progress, failure reasons and original bill details', () => {
    const html = fs.readFileSync(`${pagePath}.html`, 'utf8');
    assert.match(html, /审核并推送/); assert.match(html, /重试失败通知/);
    assert.match(html, /通知状态/); assert.match(html, /_billNotificationSummary/);
    assert.match(html, /error\.msg/); assert.match(html, /欠费清单/);
    assert.doesNotMatch(html, /审核与推送需要分别操作/);
});

test('reject requires a trimmed reason of at most 500 characters and explicit confirmation', () => {
    const p = page(), row = p.component.billManageInfo.bills[0];
    assert.equal(typeof p.component._rejectBill, 'function');
    assert.equal(typeof p.component._openBillRejection, 'function');
    p.component._openBillRejection(row);
    assert.equal(p.component.billManageInfo.rejection.billId, row.billId);
    for (const reason of ['', '   ', '错'.repeat(501)]) {
        p.component.billManageInfo.rejection.reason = reason;
        p.component._rejectBill(row);
    }
    assert.equal(p.posts.length, 0);
    assert.equal(p.confirmations.length, 0);
    p.component.billManageInfo.rejection.reason = ' 金额需核对 ';
    p.confirm(false); p.component._rejectBill(row);
    assert.equal(p.posts.length, 0);
    p.confirm(true); p.component._rejectBill(row); p.component._rejectBill(row);
    assert.equal(p.posts.length, 1);
    assert.equal(p.posts[0].url, '/fee.reviewBill');
    assert.equal(p.posts[0].body.action, 'REJECT');
    assert.equal(p.posts[0].body.notify, false);
    assert.equal(p.posts[0].body.remark, '金额需核对');
    assert.equal(p.posts[0].body.billId, row.billId);
    assert.equal(p.posts[0].body.communityId, 'community-1');
    assert.match(p.confirmations.at(-1), /金额需核对/);
});

test('reject success updates the row and refreshes filters without notification work', () => {
    const p = page(), row = p.component.billManageInfo.bills[0];
    assert.equal(typeof p.component._openBillRejection, 'function');
    p.component._openBillRejection(row);
    p.component.billManageInfo.rejection.reason = '费用有误';
    Object.assign(p.component.billManageInfo.conditions, { page: 3, row: 20 });
    p.component._rejectBill(row);
    p.posts[0].success(JSON.stringify({ code: 0, data: { billId: row.billId, reviewState: 'REJECTED',
        reviewRemark: '费用有误', reviewUserId: 'staff-1', reviewTime: '2026-11-06 12:00:00' } }));
    assert.equal(row.reviewState, 'REJECTED');
    assert.equal(row.reviewRemark, '费用有误');
    assert.equal(row.reviewUserId, 'staff-1');
    assert.equal(row.reviewTime, '2026-11-06 12:00:00');
    assert.equal(p.component.billManageInfo.reviewing[row.billId], undefined);
    assert.equal(p.component.billManageInfo.rejection.billId, '');
    assert.deepEqual(p.gets.map(request => request.url), ['/fee.listBill']);
    assert.equal(p.gets[0].params.params.page, 3);
    assert.equal(p.gets[0].params.params.row, 20);
    assert.equal(p.timers.size, 0);
});

test('rejected bill can only be explicitly resubmitted as the same draft snapshot', () => {
    const p = page(), row = p.component.billManageInfo.bills[0];
    assert.equal(typeof p.component._resubmitBill, 'function');
    row.reviewState = 'REJECTED';
    p.component._reviewBill(row); p.component._retryBillNotifications(row);
    assert.equal(p.posts.length, 0);
    p.confirm(false); p.component._resubmitBill(row);
    assert.equal(p.posts.length, 0);
    p.confirm(true); p.component._resubmitBill(row); p.component._resubmitBill(row);
    assert.equal(p.posts.length, 1);
    assert.deepEqual(p.posts[0].body, { communityId: 'community-1', billId: row.billId,
        action: 'RESUBMIT', notify: false });
    assert.match(p.confirmations.at(-1), /同一.*快照/);
    p.posts[0].success(JSON.stringify({ code: 0, data: { billId: row.billId, reviewState: 'DRAFT' } }));
    assert.equal(row.reviewState, 'DRAFT');
    assert.equal(p.posts.length, 1);
    assert.equal(p.timers.size, 0);
    assert.deepEqual(p.gets.map(request => request.url), ['/fee.listBill']);
});

test('reject and resubmit guard historical, foreign and invalid state rows', () => {
    const p = page();
    assert.equal(typeof p.component._rejectBill, 'function');
    for (const row of [bill({ curBill: 'F' }), bill({ reviewState: 'PUBLISHED' }),
        bill({ communityId: 'foreign' })]) {
        p.component._openBillRejection(row); p.component._rejectBill(row); p.component._resubmitBill(row);
    }
    p.component._resubmitBill(bill());
    p.component._resubmitBill(bill({ reviewState: 'REJECTED', curBill: 'F' }));
    p.component._resubmitBill(bill({ reviewState: 'REJECTED', communityId: 'foreign' }));
    assert.equal(p.posts.length, 0);
    assert.equal(p.confirmations.length, 0);
});

test('failed or uncertain rejection releases controls and preserves the draft and entered reason', () => {
    for (const fail of [request => request.failure(), request => request.success('bad json'),
        request => request.success(JSON.stringify({ code: 409, msg: '账单状态已变化，请刷新',
            data: { billId: 'bill-1', reviewState: 'REJECTED' } })),
        request => request.success(JSON.stringify({ code: 0, data: { billId: 'other', reviewState: 'REJECTED' } }))]) {
        const p = page(), row = p.component.billManageInfo.bills[0];
        assert.equal(typeof p.component._openBillRejection, 'function');
        p.component._openBillRejection(row); p.component.billManageInfo.rejection.reason = '需核对';
        p.component._rejectBill(row); fail(p.posts[0]);
        assert.equal(row.reviewState, 'DRAFT');
        assert.equal(p.component.billManageInfo.reviewing[row.billId], undefined);
        assert.equal(p.component.billManageInfo.rejection.reason, '需核对');
        assert.match(p.toasts.at(-1), /刷新|核对/);
        assert.equal(p.posts.length, 1);
    }
});

test('late rejection response cannot update a switched community or destroyed page', () => {
    for (const dispose of [p => p.community('community-2'), p => p.destroy()]) {
        const p = page(), row = p.component.billManageInfo.bills[0];
        assert.equal(typeof p.component._openBillRejection, 'function');
        p.component._openBillRejection(row); p.component.billManageInfo.rejection.reason = '需核对';
        p.component._rejectBill(row); dispose(p);
        p.posts[0].success(JSON.stringify({ code: 0, data: { billId: row.billId, reviewState: 'REJECTED' } }));
        assert.equal(row.reviewState, 'DRAFT');
        assert.equal(p.gets.length, 0);
        assert.equal(p.toasts.length, 0);
    }
});

test('bill review template and scoped CSS distinguish all rows and expose audit and rejection controls', () => {
    const html = fs.readFileSync(`${pagePath}.html`, 'utf8');
    const css = fs.readFileSync(path.join(__dirname, '../public/css/admin-blue.css'), 'utf8');
    assert.match(html, /<tr v-for="bill in billManageInfo.bills"[^>]*:class="_billReviewRowClass\(bill\)"/);
    assert.match(html, /审核不通过/);
    assert.match(html, /驳回原因/);
    assert.match(html, /bill.rejectReason/);
    assert.match(html, /bill.reviewRemark/);
    assert.match(html, /bill.reviewUserId/);
    assert.match(html, /bill.reviewUserName/);
    assert.match(html, /bill.reviewTime/);
    assert.match(html, /重新提交审核/);
    assert.match(html, /v-on:click="_resubmitBill\(bill\)"/);
    assert.match(html, /v-model="billManageInfo.rejection.reason"[^>]*maxlength="500"/);
    for (const [state, color] of [['draft', '#fff4cc'], ['published', '#e3f4e8'], ['rejected', '#fde8e8']]) {
        assert.match(css, new RegExp('\\.admin-bills \\.table tbody tr\\.bill-review-' + state + ' > td[^}]*' + color));
    }
    const p = page();
    assert.equal(typeof p.component._billReviewRowClass, 'function');
    for (const state of ['DRAFT', 'PUBLISHED', 'REJECTED'])
        assert.equal(p.component._billReviewRowClass(bill({ reviewState: state })), 'bill-review-' + state.toLowerCase());
    assert.equal(p.component._billReviewRowClass(bill({ reviewState: 'unknown' })), '');
});

test('list response exposes automatic queue counts without any additional status request', () => {
    const p = page();
    assert.equal(p.component.billManageInfo.automaticGeneration?.state, 'UNKNOWN');
    p.component._listbills(1, 10);
    p.respondList([bill()], { automaticGeneration: { state: 'OK', pending: 3, failed: 2, waiting: 1 } });
    assert.deepEqual(JSON.parse(JSON.stringify(p.component.billManageInfo.automaticGeneration)),
        { state: 'OK', pending: 3, failed: 2, waiting: 1 });
    const summary = p.component._billAutomaticGenerationSummary();
    assert.match(summary, /待处理.*3/);
    assert.match(summary, /失败.*2/);
    assert.match(summary, /等待.*1/);
    assert.match(summary, /重试/);
    assert.deepEqual(p.gets.map(request => request.url), ['/fee.listBill']);
});

test('unavailable, missing and invalid queue status never reveal server errors or stale counts', () => {
    const p = page();
    assert.equal(typeof p.component._billAutomaticGenerationSummary, 'function');
    for (const status of [{ state: 'UNAVAILABLE', pending: 5, error: 'SQL secret table_name' }, undefined,
        { state: 'broken', pending: 5, msg: 'SQL secret table_name' }]) {
        p.component._listbills(1, 10);
        p.respondList([], { automaticGeneration: status });
        assert.equal(p.component.billManageInfo.automaticGeneration.pending, 0);
        const summary = p.component._billAutomaticGenerationSummary();
        assert.match(summary, /查询|刷新/);
        assert.doesNotMatch(summary, /SQL|secret|table_name|5/);
    }
    p.component._listbills(1, 10);
    p.respondList([], { automaticGeneration: { state: 'OK', pending: -3, failed: 'bad', waiting: null } });
    assert.deepEqual(JSON.parse(JSON.stringify(p.component.billManageInfo.automaticGeneration)),
        { state: 'OK', pending: 0, failed: 0, waiting: 0 });
    assert.match(p.component._billAutomaticGenerationSummary(), /没有.*任务/);
});

test('queue banner uses safe summary and rejection panel explicitly preserves original snapshot', () => {
    const html = fs.readFileSync(`${pagePath}.html`, 'utf8');
    assert.match(html, /aria-label="自动出账状态"/);
    assert.match(html, /_billAutomaticGenerationSummary\(\)/);
    assert.match(html, /保留.*原金额和明细快照/);
    assert.match(html, /只记录审核结果与驳回原因/);
});
