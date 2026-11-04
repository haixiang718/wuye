/**
 入驻小区
 **/
(function (vc) {
    var DEFAULT_PAGE = 1;
    var DEFAULT_ROWS = 10;
    vc.extends({
        data: {
            billManageInfo: {
                bills: [],
                reviewing: {},
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
           $that._listbills(DEFAULT_PAGE,DEFAULT_ROWS);
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
                if (_bill.reviewState != 'DRAFT' || _info.reviewing[_bill.billId]) return;
                if (!window.confirm('确认已核对账单明细并审核发布？发布后业主可查看账单，物业可推送通知。')) return;
                _info.reviewing = Object.assign({}, _info.reviewing, { [_bill.billId]: true });
                var _finish = function () {
                    var _next = Object.assign({}, _info.reviewing); delete _next[_bill.billId]; _info.reviewing = _next;
                };
                vc.http.apiPost('/fee.reviewBill', JSON.stringify({
                    communityId: vc.getCurrentCommunity().communityId,
                    billId: _bill.billId,
                    remark: '物业审核发布'
                }), { emulateJSON: true }, function (json) {
                    _finish();
                    try {
                        var _result = JSON.parse(json); vc.toast(_result.msg || '审核结果异常，请刷新核对');
                        vc.component._listbills(_info.conditions.page || DEFAULT_PAGE, DEFAULT_ROWS);
                    } catch (e) { vc.toast('审核结果未知，请刷新核对后再操作'); }
                }, function () { _finish(); vc.toast('审核请求失败，请刷新核对后重试'); });
            },
            _listbills: function (_page, _rows) {

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
                        var _billManageInfo = JSON.parse(json);
                        vc.component.billManageInfo.total = _billManageInfo.total;
                        vc.component.billManageInfo.records = _billManageInfo.records;
                        vc.component.billManageInfo.bills = _billManageInfo.data;
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
