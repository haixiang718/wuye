/**
 入驻小区
 **/
(function (vc) {
    var DEFAULT_PAGE = 1;
    var DEFAULT_ROWS = 10;
    vc.extends({
        data: {
            billOweManageInfo: {
                billOwes: [],
                pushRequests: {},
                total: 0,
                records: 1,
                moreCondition: false,
                title: '',
                billName:'',
                conditions: {
                    ownerName: '',
                    state: '',
                    billName: '',
                    billId: '',

                }
            }
        },
        _initMethod: function () {
            $that.billOweManageInfo.conditions.billId = vc.getParam('billId');
            $that.billOweManageInfo.billName = vc.getParam('billName');

            $that._listOwebills(DEFAULT_PAGE, DEFAULT_ROWS);
        },
        _initEvent: function () {

            vc.on('billOweManage', 'listbillOwe', function (_param) {
                vc.component.billOweManageInfo.componentShow = 'billOweList';
                vc.component._listOwebills(DEFAULT_PAGE, DEFAULT_ROWS);
            });
            vc.on('pagination', 'page_event', function (_currentPage) {
                vc.component._listOwebills(_currentPage, DEFAULT_ROWS);
            });
        },
        methods: {
            _canPushBill: function (_bill) {
                var _amount = Number(_bill.billAmountOwed);
                return _bill.reviewState == 'PUBLISHED' && (_bill.state == '1000' || _bill.state == '3000')
                    && isFinite(_amount) && _amount > 0;
            },
            _isPushingBill: function (_bill) {
                var _request = vc.component.billOweManageInfo.pushRequests[_bill.oweId];
                return !!(_request && _request.sending);
            },
            _pushBill: function (_bill) {
                if (!vc.component._canPushBill(_bill) || vc.component._isPushingBill(_bill)) {
                    return;
                }
                var _info = vc.component.billOweManageInfo;
                var _previous = _info.pushRequests[_bill.oweId];
                var _message = '确认向业主推送账单？\n业主：' + _bill.ownerName
                    + '\n房屋/车位：' + _bill.payerObjName + '\n累计欠费：' + _bill.billAmountOwed + '元';
                if (_bill.state == '3000') {
                    _message += '\n该账单已催缴，再次推送可能产生重复通知，请确认。';
                }
                if (_previous && _previous.uncertain) {
                    _message += '\n上次推送结果不确定，请先核实微信受理记录，勿盲目重试。';
                }
                if (!window.confirm(_message)) {
                    return;
                }
                var _request = {
                    requestId: _previous ? _previous.requestId : vc.uuid(),
                    sending: true,
                    uncertain: false
                };
                // Replace the map so Vue observes both newly added and removed entries.
                var _requests = Object.assign({}, _info.pushRequests);
                _requests[_bill.oweId] = _request;
                _info.pushRequests = _requests;
                var _finish = function (_uncertain) {
                    var _remaining = Object.assign({}, _info.pushRequests);
                    if (_uncertain) {
                        _remaining[_bill.oweId] = {
                            requestId: _request.requestId, sending: false, uncertain: true
                        };
                    } else {
                        delete _remaining[_bill.oweId];
                    }
                    _info.pushRequests = _remaining;
                };
                var _unknown = function () {
                    _finish(true);
                    vc.toast('推送结果不确定，请先核实微信受理记录，勿盲目重试。');
                };
                vc.http.apiPost('/fee.pushBill', JSON.stringify({
                    communityId: vc.getCurrentCommunity().communityId,
                    oweId: _bill.oweId,
                    requestId: _request.requestId
                }), { emulateJSON: true }, function (json) {
                    var _result;
                    try {
                        _result = JSON.parse(json);
                        if (!_result || _result.code === undefined) {
                            _unknown();
                            return;
                        }
                    } catch (e) {
                        _unknown();
                        return;
                    }
                    _finish(_result.code == 202);
                    vc.toast(_result.msg);
                    if (_result.code == 0) {
                        vc.component._listOwebills(_info.conditions.page || DEFAULT_PAGE,
                            _info.conditions.row || DEFAULT_ROWS);
                    }
                }, _unknown);
            },
            _listOwebills: function (_page, _rows) {

                vc.component.billOweManageInfo.conditions.page = _page;
                vc.component.billOweManageInfo.conditions.row = _rows;
                vc.component.billOweManageInfo.conditions.communityId = vc.getCurrentCommunity().communityId;
                var param = {
                    params: vc.component.billOweManageInfo.conditions
                };

                //发送get请求
                vc.http.apiGet('fee.listBillOweFee',
                    param,
                    function (json, res) {
                        var _billOweManageInfo = JSON.parse(json);
                        vc.component.billOweManageInfo.total = _billOweManageInfo.total;
                        vc.component.billOweManageInfo.records = _billOweManageInfo.records;
                        vc.component.billOweManageInfo.billOwes = _billOweManageInfo.data;
                        vc.emit('pagination', 'init', {
                            total: vc.component.billOweManageInfo.records,
                            dataCount: vc.component.billOweManageInfo.total,
                            currentPage: _page
                        });
                    }, function (errInfo, error) {
                        console.log('请求失败处理');
                    }
                );
            },
            _openBillDetail: function () {


            },
            _querybillMethod: function () {
                vc.component._listOwebills(DEFAULT_PAGE, DEFAULT_ROWS);

            },
            _moreCondition: function () {
                if (vc.component.billOweManageInfo.moreCondition) {
                    vc.component.billOweManageInfo.moreCondition = false;
                } else {
                    vc.component.billOweManageInfo.moreCondition = true;
                }
            },
            _goBack:function(){
                vc.goBack();
            }


        }
    });
})(window.vc);
