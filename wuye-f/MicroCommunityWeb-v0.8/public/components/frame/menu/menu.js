/**
 菜单 处理
 **/
(function (vc) {
    var vm = new Vue({
        el: '#menu-nav',
        data: {
            menus: [],
            logo: '',
        },
        mounted: function () {
            this._initSysInfo();
            this.getMenus();
            var _that = this;
            window.addEventListener('hashchange', function () {
                _that._syncMenuWithUrl();
            });
            let _menuDiv = document.getElementById('menu-nav');
            vcFramework.eleResize.on(_menuDiv, function () {
                //console.log('resize', '大小修改了');
                vcFramework._fix_height(_menuDiv);
            });
            window.onscroll = function () {
                //为了保证兼容性，这里取两个值，哪个有值取哪一个
                //scrollTop就是触发滚轮事件时滚轮的高度
                let _menuDivb = document.getElementById('menu-nav');
                vcFramework._fix_height(_menuDivb);
            }
        },
        methods: {
            _initSysInfo: function () {
                var sysInfo = vc.getData("_sysInfo");
                if (sysInfo == null) {
                    this.logo = "HC";
                    return;
                }
                this.logo = sysInfo.logo;
            },
            _gotoIndex: function () {
                vc.jumpToPage("/")
            },
            getMenus: function () {

                var _tmpMenus = vc.getMenus();
                //浏览器缓存中能获取到
                if (_tmpMenus != null && _tmpMenus != undefined) {
                    this.miniMenu();
                    this.menus = _tmpMenus;
                    this._syncMenuWithUrl();
                    return;
                }

                var param = {
                    params: {
                        msg: this.message
                    }

                }
                //发送get请求
                vc.http.get('menu',
                    'getMenus',
                    param,
                    function (json, res) {
                        var _menus = window.localFeatures.filterMenus(JSON.parse(json));
                        if (_menus == null || _menus.length == 0) {
                            return;
                        }
                        _menus.sort(function (a, b) {
                            return a.seq - b.seq
                        });
                        var _currentMenusId = vc.getCurrentMenu() == null ? _menus[0].id : vc.getCurrentMenu();
                        vm.menus = vm.refreshMenuActive(_menus, _currentMenusId);
                        vm._syncMenuWithUrl();
                        vc.setMenus(vm.menus);
                        vm.miniMenu();
                    }, function (errInfo, error) {
                        console.log('请求失败处理');
                    }
                );
            },
            refreshMenuActive: function (jsonArray, _id) {
                for (var menuIndex = 0; menuIndex < jsonArray.length; menuIndex++) {

                    if (jsonArray[menuIndex].hasOwnProperty('childs')) {
                        var _childs = jsonArray[menuIndex].childs;
                        _childs.sort(function (_child, _newChild) {
                            return _child.seq - _newChild.seq
                        });
                        jsonArray[menuIndex].childs = _childs;
                    }

                    if (_id === jsonArray[menuIndex].id) {
                        if (jsonArray[menuIndex].active === true) {
                            //如果当前本身是打开状态，说明 需要关闭
                            jsonArray[menuIndex].active = false;
                            continue;
                        }
                        jsonArray[menuIndex].active = true;
                        continue;
                    }
                    jsonArray[menuIndex].active = false;
                }


                return jsonArray;
            },
            switchMenu: function (_id) {
                //设置菜单ID
                vc.setCurrentMenu(_id);
                vm.menus = vm.refreshMenuActive(vm.menus, _id);
                vc.setMenus(vm.menus);
                //vc._fix_height()
            },
            miniMenu: function () {

                //菜单默认为打开方式
                if (!vc.notNull(vc.getMenuState())) {
                    vc.setMenuState('ON');
                }

                if (vc.notNull(vc.getMenuState()) && vc.getMenuState() == 'ON') {
                    return;
                }

                $("body").toggleClass("mini-navbar");
                vc.setMenuState('OFF');
            },
            _menuPath: function (_href) {
                if (!_href) {
                    return null;
                }
                var _url = new URL(_href, window.location.href);
                if (_url.origin !== window.location.origin) {
                    return null;
                }
                return _url.pathname + _url.hash.split('?')[0];
            },
            _syncMenuWithUrl: function (_href) {
                var _path = this._menuPath(_href || window.location.href);
                var _selectedMenu = null;
                var _selectedChild = null;
                this.menus.some(function (_menu) {
                    var _childs = _menu.childs || [];
                    _selectedChild = _childs.find(function (_child) {
                        return _child.isShow === 'Y' && this._menuPath(_child.href) === _path;
                    }, this);
                    if (_selectedChild || (_childs.length === 0 && this._menuPath(_menu.href) === _path)) {
                        _selectedMenu = _menu;
                        return true;
                    }
                    return false;
                }, this);
                // Detail pages have no visible menu entry; keep their originating menu.
                if (!_selectedMenu) {
                    return;
                }
                this.menus.forEach(function (_menu) {
                    _menu.active = _menu === _selectedMenu;
                    (_menu.childs || []).forEach(function (_child) {
                        _child.active = _child === _selectedChild;
                    });
                });
                vc.setCurrentMenu(_selectedMenu.id);
                vc.setMenus(this.menus);
                this.$forceUpdate();
            },
            _gotoPage: function (_href) {
                this._syncMenuWithUrl(_href);
                vc.jumpToPage(_href);
            }

        },

    });

})(window.vc)
