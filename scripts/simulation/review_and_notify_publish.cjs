'use strict';
// Only a provenance-checked, unbound random owner may be used by this check.
const fs = require('node:fs'), path = require('node:path');
const {chromium} = require('../../.tools/browser/node_modules/playwright-core');
const root = path.resolve(__dirname, '../..'), folder = path.join(root, '.runtime/review-and-notify-flow');
const fixture = JSON.parse(fs.readFileSync(path.join(folder, 'fixture.json'), 'utf8'));
if (!/^WXD[a-f0-9]{12}$/.test(fixture.prefix)
    || fixture.label !== 'LOCAL WECHAT DIRECT FLOW ONLY - DO NOT PAY'
    || fixture.ids.owner !== fixture.prefix + 'O' || !fixture.billId || !fixture.oweId) throw Error('Unexpected fixture provenance');
const report = {checks: [], notifications: 0, payments: 0, pageErrors: []};
function check(name, value) {report.checks.push({name, passed: !!value}); if (!value) throw Error('Combined flow assertion: ' + name);}
async function status(page, communityId = fixture.communityId) {
    return page.evaluate(({communityId, billId}) => new Promise((resolve, reject) => {
        vc.http.apiGet('/fee.billNotificationStatus', {params: {communityId, billId}},
            json => {try {resolve(JSON.parse(json));} catch {reject(Error('Invalid progress response'));}},
            () => reject(Error('Progress request failed')));
    }), {communityId, billId: fixture.billId});
}
async function terminal(page) {
    const deadline = Date.now() + 45000;
    while (Date.now() < deadline) {
        const reply = await status(page), notification = reply.data && reply.data.notification;
        if (reply.code === 0 && notification && ['COMPLETED', 'FAILED'].includes(notification.state)) return notification;
        await page.waitForTimeout(500);
    }
    throw Error('Fixture notification task did not stop');
}
(async () => {
    const browser = await chromium.launch({executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true});
    try {
        const context = await browser.newContext({viewport: {width: 1440, height: 1100}});
        await context.addCookies(JSON.parse(fs.readFileSync(path.join(root, '.runtime/browser-cookies.json'), 'utf8').replace(/^\uFEFF/, '')));
        const page = await context.newPage(); page.setDefaultTimeout(30000);
        page.on('pageerror', error => report.pageErrors.push(error.message)); page.on('dialog', dialog => dialog.accept());
        await page.goto('http://127.0.0.1:3000/admin.html', {waitUntil: 'networkidle'});
        await page.waitForFunction(() => window.vc && vc.getCurrentCommunity());
        check('expected_community', await page.evaluate(() => vc.getCurrentCommunity().communityId) === fixture.communityId);
        await page.goto('http://127.0.0.1:3000/admin.html#/pages/property/billManage', {waitUntil: 'networkidle'});
        const row = page.locator('tbody tr').filter({hasText: fixture.billId});
        const publishResponse = page.waitForResponse(r => r.url().includes('/app/fee.reviewBill') && r.request().method() === 'POST');
        await row.getByRole('button', {name: '审核并推送', exact: true}).click();
        const response = await publishResponse, reply = await response.json();
        const command = response.request().postDataJSON();
        check('combined_button_requests_notification', command.notify === true && command.billId === fixture.billId);
        check('review_publishes_and_queues', response.status() === 200 && reply.code === 0 && reply.data.reviewState === 'PUBLISHED');
        await row.getByText('已审核发布', {exact: true}).waitFor();
        const notification = await terminal(page);
        check('unbound_failure_is_actionable', notification.failed === 1 && notification.accepted === 0
            && notification.canRetry === true && notification.errors.some(error => error.msg.includes('绑定')));
        report.terminalUnboundFailure = true;
        const duplicate = await page.evaluate(({communityId, billId}) => new Promise((resolve, reject) => {
            vc.http.apiPost('/fee.reviewBill', JSON.stringify({communityId, billId, notify: true}), {emulateJSON: true},
                json => {try {resolve(JSON.parse(json));} catch {reject(Error('Invalid duplicate reply'));}}, () => reject(Error('Duplicate request failed')));
        }), {communityId: fixture.communityId, billId: fixture.billId});
        check('repeat_publish_does_not_requeue', duplicate.code === 0 && duplicate.data.notification.state === notification.state);
        await page.reload({waitUntil: 'networkidle'});
        const refreshed = page.locator('tbody tr').filter({hasText: fixture.billId});
        await refreshed.getByRole('button', {name: '重试失败通知', exact: true}).waitFor();
        const retryResponse = page.waitForResponse(r => r.url().includes('/app/fee.reviewBill') && r.request().method() === 'POST');
        await refreshed.getByRole('button', {name: '重试失败通知', exact: true}).click();
        const retried = await retryResponse, retriedBody = retried.request().postDataJSON();
        check('retry_is_explicit_and_scoped', retriedBody.retryNotifications === true && retriedBody.notify === true && retriedBody.billId === fixture.billId);
        const finalState = await terminal(page);
        check('retry_preserves_published_bill_and_failure', finalState.failed === 1 && finalState.accepted === 0 && finalState.processed === finalState.total);
        await refreshed.getByRole('button', {name: '重试失败通知', exact: true}).waitFor();
        const panel = page.getByRole('region', {name: '账单通知状态', exact: true});
        await panel.locator('li').filter({hasText: '尚未完成公众号微信绑定'}).waitFor();
        check('page_poll_shows_terminal_failure_reason', true);
        report.retryFailedOnly = true;
        // The legacy front rejects an invalid community before the listener; its
        // exception currently maps to HTTP 500. Verify no status data escapes.
        const denied = await page.request.get('http://127.0.0.1:3000/app/fee.billNotificationStatus', {
            params: {communityId: 'unmanaged-community', billId: fixture.billId},
            headers: {'APP-ID': '8000418004', 'TRANSACTION-ID': 'local-scope-check', 'REQ-TIME': '20261104130000', SIGN: ''}
        });
        check('invalid_community_returns_no_status_data', denied.status() >= 400 && !(await denied.text()).includes('"notification"'));
        check('no_page_errors', report.pageErrors.length === 0);
        await page.screenshot({path: path.join(folder, 'combined-published.png'), fullPage: true});
        report.passed = true;
    } catch (error) {report.failure = error.stack; throw error;}
    finally {await browser.close(); fs.writeFileSync(path.join(folder, 'publication.json'), JSON.stringify(report, null, 2));}
    console.log(JSON.stringify({passed: true, checks: report.checks.length, notifications: 0, payments: 0}));
})().catch(() => {console.error('Combined local admin check failed; inspect private scoped evidence.'); process.exitCode = 2;});
