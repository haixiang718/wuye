/**
 入驻小区
 **/
(function (vc) {
    var DEFAULT_PAGE = 1;
    var DEFAULT_ROWS = 10;
    var _notificationTimer = null;
    var _notificationDisposed = false;
    var _notificationEpoch = 0;
    var _notificationSequence = 0;
    function _notificationScope(_info, _communityId) {
        var _community = vc.getCurrentCommunity();
        return !_notificationDisposed && vc.component && vc.component.billManageInfo === _info
            && _community && _community.communityId == _communityId;
    }
    function _clearNotificationTimer() {
        if (_notificationTimer !== null) window.clearTimeout(_notificationTimer);
        _notificationTimer = null;
    }
    vc.extends({
        data: {
            billManageInfo: {
                bills: [],
                reviewing: {},
                notifications: {},
                notificationLoading: {},
                notificationErrors: {},
                notificationSelection: '',
                generation: {
                    show: false,
                    configs: [],
                    configId: '',
                    feeName: '',
                    billType: '',
                    communityId: '',
                    page: 1,
                    pages: 1,
                    loading: false,
                    generating: false,
                    result: ''
                },
                total: 0,
                records: 1,
                moreCondition: false,
                title: '',
                conditions: {
                    title: '',
                    curBill: 'T',
                    billName: '',
                    billId: '',

                }
            }
        },
        _initMethod: function () {
           _notificationDisposed = false;
           $that._listbills(DEFAULT_PAGE,DEFAULT_ROWS);
        },
        _destroyedMethod: function () {
            _notificationDisposed = true;
            _notificationEpoch++;
            _clearNotificationTimer();
        },
        _initEvent: function () {

            vc.on('billManage', 'listbill', function (_param) {
                vc.component.billManageInfo.componentShow = 'billList';
                vc.component._listbills(DEFAULT_PAGE, DEFAULT_ROWS);
            });
            vc.on('pagination', 'page_event', function (_currentPage) {
                vc.component._listbills(_currentPage, DEFAULT_ROWS);
            });
        },
        methods: {
            _openBillGeneration: function () {
                vc.component.billManageInfo.generation.show = true;
                vc.component._loadBillFeeConfigs(DEFAULT_PAGE);
            },
            _loadBillFeeConfigs: function (_page) {
                var _generation = vc.component.billManageInfo.generation;
                if (_generation.loading || _generation.generating) return;
                _generation.loading = true;
                _generation.configId = '';
                _generation.configs = [];
                _generation.result = '';
                _generation.communityId = vc.getCurrentCommunity().communityId;
                var _params = {
                    params: {
                        communityId: _generation.communityId,
                        feeName: _generation.feeName.trim(),
                        billType: _generation.billType,
                        page: _page,
                        row: DEFAULT_ROWS
                    }
                };
                var _failed = function (_message) {
                    _generation.loading = false;
                    _generation.pages = 1;
                    _generation.result = _message;
                    vc.toast(_message);
                };
                vc.http.get('feeConfigManage', 'list', _params, function (json) {
                    try {
                        var _result = JSON.parse(json);
                        if (!_result || (_result.code !== undefined && _result.code != 0)
                            || !Array.isArray(_result.feeConfigs)) {
                            _failed((_result && _result.msg) || '收费项目加载失败，请重新查询');
                            return;
                        }
                        _generation.configs = _result.feeConfigs.filter(function (_config) {
                            return ['001', '002', '003', '005'].indexOf(_config.billType) >= 0
                                && (!_config.communityId || _config.communityId == _generation.communityId);
                        });
                        _generation.page = _page;
                        _generation.pages = Math.max(1, Number(_result.records)
                            || Math.ceil(Number(_result.total || 0) / DEFAULT_ROWS));
                        _generation.loading = false;
                    } catch (e) { _failed('收费项目响应异常，请重新查询'); }
                }, function () { _failed('收费项目加载失败，请检查网络后重新查询'); });
            },
            _generateBill: function () {
                var _info = vc.component.billManageInfo;
                var _generation = _info.generation;
                if (_generation.loading || _generation.generating) return;
                var _communityId = vc.getCurrentCommunity().communityId;
                if (_generation.communityId != _communityId) {
                    vc.toast('当前小区已变化，请重新查询并选择收费项目');
                    return;
                }
                var _selected = _generation.configs.filter(function (_config) {
                    return _config.configId == _generation.configId;
                })[0];
                if (!_selected || ['001', '002', '003', '005'].indexOf(_selected.billType) < 0) {
                    vc.toast('请先选择支持出账的收费项目');
                    return;
                }
                _generation.generating = true;
                _generation.result = '';
                var _finish = function (_message) {
                    _generation.generating = false;
                    _generation.result = _message;
                    vc.toast(_message);
                };
                vc.http.apiPost('/fee.generateBill', JSON.stringify({
                    communityId: _communityId,
                    configId: _selected.configId
                }), { emulateJSON: true }, function (json) {
                    var _result;
                    try { _result = JSON.parse(json); }
                    catch (e) { _finish('生成结果未知，请刷新账单核对后重试'); return; }
                    if (!_result || _result.code === undefined || _result.code === null || _result.code === '') {
                        _finish('生成结果未知，请刷新账单核对后重试');
                        return;
                    }
                    if (_result.code != 0) {
                        _finish(_result.msg || '账单生成失败，请重新查询收费项目后重试');
                        return;
                    }
                    var _bill = _result.data || {};
                    var _message = _result.msg || (_bill.existing ? '当期账单已存在，本次未重复生成' : '已生成待审核账单');
                    if (_bill.billId) _message += '；账单编号：' + _bill.billId;
                    if (_bill.reviewState) _message += '；状态：'
                        + (_bill.reviewState == 'PUBLISHED' ? '已审核发布' : _bill.reviewState == 'DRAFT' ? '待审核' : _bill.reviewState);
                    _finish(_message);
                    vc.component._listbills(_info.conditions.page || DEFAULT_PAGE, _info.conditions.row || DEFAULT_ROWS);
                }, function () { _finish('生成请求失败，结果未知，请刷新账单核对后重试'); });
            },
            _reviewBill: function (_bill) {
                var _info = vc.component.billManageInfo;
                if (_bill.reviewState != 'DRAFT' || _bill.curBill != 'T' || _info.reviewing[_bill.billId]) return;
                if (!window.confirm('确认已核对账单明细并审核推送？审核后 H5 可查看账单，并在后台发送公众号缴费通知。已通知的明细不会重复发送。')) return;
                vc.component._submitBillPublication(_bill, false);
            },
            _retryBillNotifications: function (_bill) {
                if (!vc.component._canRetryBillNotifications(_bill)) return;
                var _notification = vc.component.billManageInfo.notifications[_bill.billId];
                var _message = _notification.state == 'NONE'
                    ? '确认向业主发送这张已审核账单的公众号通知？仅处理未通知明细，已通知和已缴费明细会跳过。'
                    : '确认重试失败通知？仅重试允许重试的失败明细，已受理及结果不确定的通知不会重复发送。';
                if (!window.confirm(_message)) return;
                vc.component._submitBillPublication(_bill, true);
            },
            _canRetryBillNotifications: function (_bill) {
                var _info = vc.component.billManageInfo;
                var _notification = _info.notifications[_bill.billId];
                return _bill.reviewState == 'PUBLISHED' && _bill.curBill == 'T' && !_info.reviewing[_bill.billId]
                    && _notification && _notification.canRetry === true
                    && ['QUEUED', 'RUNNING'].indexOf(_notification.state) < 0;
            },
            _submitBillPublication: function (_bill, _retry) {
                var _info = vc.component.billManageInfo;
                var _community = vc.getCurrentCommunity();
                if (!_community || !_community.communityId || _info.reviewing[_bill.billId]) return;
                var _communityId = _community.communityId;
                _info.reviewing = Object.assign({}, _info.reviewing, { [_bill.billId]: true });
                var _finish = function () {
                    var _next = Object.assign({}, _info.reviewing); delete _next[_bill.billId]; _info.reviewing = _next;
                };
                var _request = {
                    communityId: _communityId,
                    billId: _bill.billId,
                    remark: '物业审核并推送',
                    notify: true
                };
                if (_retry) _request.retryNotifications = true;
                vc.http.apiPost('/fee.reviewBill', JSON.stringify(_request), { emulateJSON: true }, function (json) {
                    if (!_notificationScope(_info, _communityId)) return;
                    _finish();
                    try {
                        var _result = JSON.parse(json);
                        if (!_result || _result.code === undefined || _result.code === null) throw new Error('Invalid publication response');
                        var _data = _result.data;
                        if (_data && _data.billId == _bill.billId && _data.reviewState == 'PUBLISHED') {
                            _bill.reviewState = 'PUBLISHED';
                            _info.bills.forEach(function (_row) { if (_row.billId == _bill.billId) _row.reviewState = 'PUBLISHED'; });
                            vc.component._applyBillNotification(_bill.billId, _data);
                            _info.notificationSelection = _bill.billId;
                            vc.toast(_result.msg || '已审核发布，请查看通知状态');
                        } else {
                            vc.toast(_result.msg || '审核与通知结果未知，请刷新账单核对');
                        }
                        vc.component._listbills(_info.conditions.page || DEFAULT_PAGE, _info.conditions.row || DEFAULT_ROWS);
                        vc.component._scheduleBillNotificationPoll();
                    } catch (e) { vc.toast('审核与通知结果未知，请刷新账单核对后再操作'); }
                }, function () {
                    if (!_notificationScope(_info, _communityId)) return;
                    _finish(); vc.toast('审核与通知结果未知，请刷新账单及通知状态核对，勿连续重复操作');
                });
            },
            _applyBillNotification: function (_billId, _data) {
                var _notification = _data && _data.notification;
                if (!_data || _data.billId != _billId || _data.reviewState != 'PUBLISHED' || !_notification
                    || ['NONE', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'].indexOf(_notification.state) < 0
                    || !Array.isArray(_notification.errors)) throw new Error('Invalid notification status');
                var _info = vc.component.billManageInfo;
                _info.notifications = Object.assign({}, _info.notifications, { [_billId]: _notification });
                var _errors = Object.assign({}, _info.notificationErrors); delete _errors[_billId]; _info.notificationErrors = _errors;
            },
            _showBillNotification: function (_bill) {
                vc.component.billManageInfo.notificationSelection = _bill.billId;
                vc.component._refreshBillNotification(_bill, true);
            },
            _refreshBillNotification: function (_bill, _showErrors) {
                var _info = vc.component.billManageInfo;
                var _community = vc.getCurrentCommunity();
                if (!_community || _bill.reviewState != 'PUBLISHED' || _info.notificationLoading[_bill.billId]) return;
                var _communityId = _community.communityId;
                if (!_notificationScope(_info, _communityId)) return;
                var _epoch = _notificationEpoch;
                var _sequence = ++_notificationSequence;
                _info.notificationLoading = Object.assign({}, _info.notificationLoading, { [_bill.billId]: _sequence });
                var _active = function () {
                    return _notificationScope(_info, _communityId) && _epoch == _notificationEpoch
                        && _info.bills.some(function (_row) { return _row.billId == _bill.billId; });
                };
                var _finish = function (_message) {
                    if (!_active() || _info.notificationLoading[_bill.billId] != _sequence) return false;
                    var _loading = Object.assign({}, _info.notificationLoading); delete _loading[_bill.billId]; _info.notificationLoading = _loading;
                    if (_message) {
                        _info.notificationErrors = Object.assign({}, _info.notificationErrors, { [_bill.billId]: _message });
                        if (_showErrors) vc.toast(_message);
                    }
                    return true;
                };
                vc.http.apiGet('/fee.billNotificationStatus', { params: { communityId: _communityId, billId: _bill.billId } }, function (json) {
                    if (!_active()) return;
                    try {
                        var _result = JSON.parse(json);
                        if (!_result || _result.code != 0 || !_result.data) throw new Error('Invalid status response');
                        vc.component._applyBillNotification(_bill.billId, _result.data);
                        _finish();
                    } catch (e) { _finish('通知状态查询失败，请刷新核对；这不会撤销已审核账单'); }
                    vc.component._scheduleBillNotificationPoll();
                }, function () {
                    if (!_finish('通知状态查询失败，请检查网络后刷新；通知可能仍在后台处理')) return;
                    vc.component._scheduleBillNotificationPoll();
                });
            },
            _scheduleBillNotificationPoll: function () {
                _clearNotificationTimer();
                var _info = vc.component.billManageInfo;
                var _community = vc.getCurrentCommunity();
                if (!_community || !_notificationScope(_info, _community.communityId)) return;
                var _communityId = _community.communityId;
                var _activeBills = function () {
                    return _info.bills.filter(function (_bill) {
                        var _notification = _info.notifications[_bill.billId];
                        return _bill.reviewState == 'PUBLISHED' && _notification
                            && ['QUEUED', 'RUNNING'].indexOf(_notification.state) >= 0;
                    });
                };
                if (!_activeBills().length) return;
                var _epoch = _notificationEpoch;
                _notificationTimer = window.setTimeout(function () {
                    _notificationTimer = null;
                    if (!_notificationScope(_info, _communityId) || _epoch != _notificationEpoch) return;
                    _activeBills().forEach(function (_bill) { vc.component._refreshBillNotification(_bill, false); });
                    vc.component._scheduleBillNotificationPoll();
                }, 3000);
            },
            _billNotificationLabel: function (_billId) {
                var _notification = vc.component.billManageInfo.notifications[_billId];
                if (!_notification) return '待查询通知状态';
                var _names = { NONE: '尚无合并通知任务', QUEUED: '通知排队中', RUNNING: '通知处理中',
                    COMPLETED: '通知已处理', FAILED: '通知未完成' };
                return _names[_notification.state] || '通知状态待核对';
            },
            _billNotificationSummary: function (_billId) {
                var _notification = vc.component.billManageInfo.notifications[_billId];
                if (!_notification) return '请选择已审核账单查询通知状态';
                if (_notification.state == 'NONE') return '尚无合并通知任务；可点击“通知业主”，系统会跳过已通知和已缴费明细。';
                return vc.component._billNotificationLabel(_billId) + '：已处理 ' + Number(_notification.processed || 0)
                    + ' / ' + Number(_notification.total || 0) + ' 条；微信受理 ' + Number(_notification.accepted || 0)
                    + '，失败 ' + Number(_notification.failed || 0) + '，结果待核对 ' + Number(_notification.uncertain || 0)
                    + '，跳过 ' + Number(_notification.skipped || 0) + '。微信受理不代表业主已阅读。';
            },
            _listbills: function (_page, _rows) {
                _clearNotificationTimer();
                var _epoch = ++_notificationEpoch;
                var _info = vc.component.billManageInfo;
                var _community = vc.getCurrentCommunity();
                if (!_community || !_community.communityId) return;
                var _communityId = _community.communityId;
                _info.notificationLoading = {};
                vc.component.billManageInfo.conditions.page = _page;
                vc.component.billManageInfo.conditions.row = _rows;
                vc.component.billManageInfo.conditions.communityId = vc.getCurrentCommunity().communityId;
                var param = {
                    params: vc.component.billManageInfo.conditions
                };

                //发送get请求
                vc.http.apiGet('fee.listBill',
                    param,
                    function (json, res) {
                        if (!_notificationScope(_info, _communityId) || _epoch != _notificationEpoch) return;
                        var _billManageInfo = JSON.parse(json);
                        vc.component.billManageInfo.total = _billManageInfo.total;
                        vc.component.billManageInfo.records = _billManageInfo.records;
                        vc.component.billManageInfo.bills = _billManageInfo.data;
                        if (!_info.bills.some(function (_bill) { return _bill.billId == _info.notificationSelection; })) _info.notificationSelection = '';
                        _info.bills.forEach(function (_bill) {
                            if (_bill.reviewState == 'PUBLISHED') vc.component._refreshBillNotification(_bill, false);
                        });
                        vc.component._scheduleBillNotificationPoll();
                        vc.emit('pagination', 'init', {
                            total: vc.component.billManageInfo.records,
                            dataCount: vc.component.billManageInfo.total,
                            currentPage: _page
                        });
                    }, function (errInfo, error) {
                        console.log('请求失败处理');
                    }
                );
            },
            _openBillDetail: function (_bill) {
                vc.jumpToPage('/admin.html#/pages/property/billOweManage?'+vc.objToGetParam(_bill));

            },
            _querybillMethod: function () {
                vc.component._listbills(DEFAULT_PAGE, DEFAULT_ROWS);

            },
            _moreCondition: function () {
                if (vc.component.billManageInfo.moreCondition) {
                    vc.component.billManageInfo.moreCondition = false;
                } else {
                    vc.component.billManageInfo.moreCondition = true;
                }
            }


        }
    });
})(window.vc);
