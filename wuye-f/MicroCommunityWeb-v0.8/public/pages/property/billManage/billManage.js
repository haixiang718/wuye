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
