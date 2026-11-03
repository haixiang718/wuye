/**
 入驻小区
 **/
(function (vc) {
    var DEFAULT_PAGE = 1;
    var DEFAULT_ROWS = 30;
    vc.extends({
        data: {
            staffFeeManageInfo: {
                staffFees: [],
                staffFeeTypes:[],
                total: 0,
                records: 1,
                currentPage: 1,
                moreCondition: false,
                name: '',
                conditions: {
                    communityId: vc.getCurrentCommunity().communityId,
                    startTime: '',
                    endTime: '',
                    userCode:''
                }
            }
        },
        _initMethod: function () {
            vc.component._initDate();
            vc.component._liststaffFees(DEFAULT_PAGE, DEFAULT_ROWS);
        },
        _initEvent: function () {
            vc.on('pagination', 'page_event', function (_currentPage) {
                vc.component._liststaffFees(_currentPage, DEFAULT_ROWS);
            });
        },
        methods: {
            _initDate:function(){
                $(".start_time").datetimepicker({
                    language: 'zh-CN',
                    fontAwesome: 'fa',
                    format: 'yyyy-mm-dd',
                    minView: "month",
                    initialDate: new Date(),
                    autoClose: 1,
                    todayBtn: true
                });
                $(".end_time").datetimepicker({
                    language: 'zh-CN',
                    fontAwesome: 'fa',
                    format: 'yyyy-mm-dd',
                    minView: "month",
                    initialDate: new Date(),
                    autoClose: 1,
                    todayBtn: true
                });
                $('.start_time').datetimepicker()
                    .on('changeDate', function (ev) {
                        var value = $(".start_time").val();
                        vc.component.staffFeeManageInfo.conditions.startTime = value ;
                    });
                $('.end_time').datetimepicker()
                    .on('changeDate', function (ev) {
                        var value = $(".end_time").val();
                        vc.component.staffFeeManageInfo.conditions.endTime = value ;
                    });
            },
            _liststaffFees: function (_page, _rows) {
                var conditions = vc.component.staffFeeManageInfo.conditions;
                if (conditions.startTime && conditions.endTime && conditions.startTime > conditions.endTime) {
                    vc.toast('开始日期不能晚于结束日期');
                    return;
                }
                vc.component.staffFeeManageInfo.conditions.page = _page;
                vc.component.staffFeeManageInfo.conditions.row = _rows;
                var param = {
                    params: vc.component.staffFeeManageInfo.conditions
                };

                //发送get请求
                vc.http.get('staffFeeManage',
                    'list',
                    param,
                    function (json, res) {
                        var _staffFeeManageInfo = JSON.parse(json);
                        vc.component.staffFeeManageInfo.total = Number(_staffFeeManageInfo.total) || 0;
                        vc.component.staffFeeManageInfo.records = Math.ceil(vc.component.staffFeeManageInfo.total / _rows);
                        vc.component.staffFeeManageInfo.currentPage = _page;
                        vc.component.staffFeeManageInfo.staffFees = _staffFeeManageInfo.staffFees || [];
                        vc.emit('pagination', 'init', {
                            total: vc.component.staffFeeManageInfo.records,
                            dataCount: vc.component.staffFeeManageInfo.total,
                            currentPage: _page
                        });
                    }, function (errInfo, error) {
                        console.log('请求失败处理');
                    }
                );
            },
            _querystaffFeeMethod: function () {
                vc.component._liststaffFees(DEFAULT_PAGE, DEFAULT_ROWS);

            },
            _moreCondition: function () {
                if (vc.component.staffFeeManageInfo.moreCondition) {
                    vc.component.staffFeeManageInfo.moreCondition = false;
                } else {
                    vc.component.staffFeeManageInfo.moreCondition = true;
                }
            },
            _exportExcel:function () {
                if (vc.component.staffFeeManageInfo.staffFees.length === 0) {
                    vc.toast('当前页没有可导出的收费记录');
                    return;
                }
                var blob = new Blob(['\uFEFF' + vc.component._staffFeeCsv()], {type: 'text/csv;charset=utf-8;'});
                var url = URL.createObjectURL(blob);
                var link = document.createElement('a');
                link.href = url;
                link.download = '员工收费明细-第' + vc.component.staffFeeManageInfo.currentPage + '页.csv';
                document.body.appendChild(link);
                link.click();
                link.remove();
                setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
            },
            _staffFeeCsv:function () {
                function cell(value, forceText) {
                    value = value === undefined || value === null ? '' : String(value);
                    if (forceText || /^\s*[=+@-]/.test(value)) { value = '\t' + value; }
                    return '"' + value.replace(/"/g, '""') + '"';
                }
                var rows = [['收费时间','员工名称','员工编码','收费对象','收费项目','支付方式','应收金额','实收金额','收费流水']
                    .map(function (value) { return cell(value); }).join(',')];
                vc.component.staffFeeManageInfo.staffFees.forEach(function (fee) {
                    rows.push([fee.createTime,fee.userName,fee.userId,fee.payObjName,fee.feeName,fee.primeRateName,
                        fee.receivableAmount,fee.receivedAmount,fee.detailId].map(function (value, index) {
                            return cell(value, index === 2 || index === 8);
                        }).join(','));
                });
                return rows.join('\r\n');
            }
        }
    });
})(window.vc);
