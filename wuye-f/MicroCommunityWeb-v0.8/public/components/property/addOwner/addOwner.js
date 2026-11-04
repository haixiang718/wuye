(function (vc) {

    vc.extends({
        propTypes: {
            notifyLoadDataComponentName: vc.propTypes.string,
            componentTitle: vc.propTypes.string // 组件名称
        },
        data: {
            addOwnerInfo: {
                componentTitle: $props.componentTitle,
                name: '',
                age: '',
                link: '',
                sex: '',
                ownerTypeCd: '-1',
                remark: '',
                ownerId: '',
                ownerPhoto: '',
                idCard: '',
                videoPlaying: true,
                mediaStreamTrack: null,
                saving: false,
                selectedRooms: [],
                roomOptions: [],
                roomFloorNum: '',
                roomNum: '',
                roomPage: 1,
                roomPages: 0,
                roomRecords: 0,
                roomLoading: false,
                roomRequestId: 0,
                roomCommunityId: vc.getCurrentCommunity().communityId,
                attrs: []
            }
        },
        _initMethod: function () {
            $that._loadOwnerAttrSpec();
        },
        _initEvent: function () {
            vc.on('addOwner', 'openAddOwnerModal', function (_ownerId) {
                if (vc.component.addOwnerInfo.saving) {
                    return;
                }
                vc.component.clearAddOwnerInfo();
                if (_ownerId != null) {
                    vc.component.addOwnerInfo.ownerId = _ownerId;
                }
                $('#addOwnerModel').modal('show');
                vc.component._initAddOwnerMedia();
            });
            $('#addOwnerModel').on('hide.bs.modal', function (event) {
                if (vc.component.addOwnerInfo.saving) {
                    event.preventDefault();
                    return;
                }
                vc.component._closeVedio();
                // A dismissed modal must not accept an in-flight room lookup.
                vc.component.addOwnerInfo.roomRequestId++;
                vc.component.addOwnerInfo.roomLoading = false;
            });
        },
        methods: {
            _syncOwnerRoomCommunity: function () {
                let info = vc.component.addOwnerInfo;
                let communityId = vc.getCurrentCommunity().communityId;
                if (info.roomCommunityId == communityId) {
                    return true;
                }
                info.roomCommunityId = communityId;
                info.selectedRooms = [];
                info.roomOptions = [];
                info.roomFloorNum = '';
                info.roomNum = '';
                info.roomPage = 1;
                info.roomPages = 0;
                info.roomRecords = 0;
                info.roomLoading = false;
                info.roomRequestId++;
                return false;
            },
            isOwnerRoomSelected: function (roomId) {
                return vc.component.addOwnerInfo.selectedRooms.some(function (room) {
                    return room.roomId === roomId;
                });
            },
            toggleOwnerRoom: function (room) {
                let info = vc.component.addOwnerInfo;
                if (info.saving || info.componentTitle !== '业主' || !room || !room.roomId) {
                    return;
                }
                if (!vc.component._syncOwnerRoomCommunity()) {
                    vc.toast('小区已切换，请重新查询并选择房屋');
                    return;
                }
                if (vc.component.isOwnerRoomSelected(room.roomId)) {
                    vc.component.removeOwnerRoom(room.roomId);
                    return;
                }
                if (info.selectedRooms.length >= 50) {
                    vc.toast('一次最多选择50套房屋');
                    return;
                }
                info.selectedRooms.push(room);
            },
            removeOwnerRoom: function (roomId) {
                let info = vc.component.addOwnerInfo;
                if (!info.saving) {
                    info.selectedRooms = info.selectedRooms.filter(function (room) {
                        return room.roomId !== roomId;
                    });
                }
            },
            loadOwnerRooms: function (page) {
                let info = vc.component.addOwnerInfo;
                if (info.saving || info.componentTitle !== '业主') {
                    return;
                }
                vc.component._syncOwnerRoomCommunity();
                let requestId = ++info.roomRequestId;
                let communityId = info.roomCommunityId;
                info.roomLoading = true;
                info.roomOptions = [];
                info.roomPage = page;
                function isCurrentRequest() {
                    if (vc.component.addOwnerInfo !== info) {
                        return false;
                    }
                    if (vc.getCurrentCommunity().communityId !== communityId) {
                        vc.component._syncOwnerRoomCommunity();
                        return false;
                    }
                    return info.roomRequestId === requestId;
                }
                vc.http.get('searchRoom', 'listRoom', {
                    params: {
                        communityId: communityId,
                        page: page,
                        row: 10,
                        roomFlag: '2',
                        floorNum: info.roomFloorNum,
                        roomNum: info.roomNum
                    }
                }, function (json) {
                    if (!isCurrentRequest()) {
                        return;
                    }
                    info.roomLoading = false;
                    try {
                        let result = JSON.parse(json);
                        // The legacy room endpoint omits rooms when no matches exist.
                        if (result.rooms == null && Number(result.total) === 0 && Number(result.records) === 0) {
                            result.rooms = [];
                        }
                        if (!Array.isArray(result.rooms)) {
                            vc.toast(result.msg || '查询房屋失败，请重试');
                            return;
                        }
                        info.roomOptions = result.rooms;
                        // This endpoint names the row count total and the page count records.
                        info.roomRecords = Number(result.total) || 0;
                        info.roomPages = Number(result.records) || Math.ceil(info.roomRecords / 10);
                    } catch (error) {
                        vc.toast('查询房屋失败，请重试');
                    }
                }, function () {
                    if (isCurrentRequest()) {
                        info.roomLoading = false;
                        vc.toast('查询房屋失败，请重试');
                    }
                });
            },
            addOwnerValidate: function () {
                return vc.validate.validate({
                    addOwnerInfo: vc.component.addOwnerInfo
                }, {
                    'addOwnerInfo.name': [
                        {
                            limit: "required",
                            param: "",
                            errInfo: "姓名不能为空"
                        },
                        {
                            limit: "maxin",
                            param: "2,10",
                            errInfo: "姓名长度必须在2位至10位"
                        },
                    ],
                    'addOwnerInfo.age': [

                        {
                            limit: "num",
                            param: "",
                            errInfo: "年龄不是有效的数字"
                        },
                    ],
                    'addOwnerInfo.sex': [
                        {
                            limit: "required",
                            param: "",
                            errInfo: "性别不能为空"
                        }
                    ],
                    'addOwnerInfo.link': [
                        {
                            limit: "required",
                            param: "",
                            errInfo: "手机号不能为空"
                        },
                        {
                            limit: "phone",
                            param: "",
                            errInfo: "不是有效的手机号"
                        }
                    ],
                    'addOwnerInfo.idCard': [
                        {
                            limit: "idCard",
                            param: "",
                            errInfo: "身份证格式错误"
                        }
                    ],
                    'addOwnerInfo.ownerTypeCd': [
                        {
                            limit: "required",
                            param: "",
                            errInfo: "人员类型不能为空"
                        }
                    ],
                    'addOwnerInfo.remark': [

                        {
                            limit: "maxLength",
                            param: "200",
                            errInfo: "备注长度不能超过200位"
                        }
                    ]

                });
            },
            saveOwnerInfo: function () {
                let info = vc.component.addOwnerInfo;
                if (info.saving) {
                    return;
                }
                if (info.componentTitle === '业主' && !vc.component._syncOwnerRoomCommunity()) {
                    vc.toast('小区已切换，请确认资料并重新选择房屋');
                    return;
                }
                if (info.componentTitle === '业主' && info.selectedRooms.length && !String(info.idCard || '').trim()) {
                    vc.toast('绑定房屋时必须填写身份证，供微信端身份核验');
                    return;
                }
                if (!vc.component.addOwnerValidate()) {
                    vc.toast(vc.validate.errInfo);

                    return;
                }

                // Project business fields explicitly: camera objects and picker state stay local.
                let payload = {};
                ['componentTitle', 'name', 'age', 'link', 'sex', 'ownerTypeCd', 'remark',
                    'ownerId', 'ownerPhoto', 'idCard', 'attrs'].forEach(function (field) {
                    payload[field] = info[field];
                });
                payload.communityId = vc.getCurrentCommunity().communityId;
                if (info.componentTitle === '业主' && info.selectedRooms.length) {
                    payload.roomIds = info.selectedRooms.map(function (room) { return room.roomId; });
                }
                let body = JSON.stringify(payload);
                info.saving = true;
                vc.http.post(
                    'addOwner',
                    'saveOwner',
                    body,
                    {
                        emulateJSON: true
                    },
                    function (json, res) {
                        info.saving = false;
                        let _json;
                        try {
                            _json = JSON.parse(json);
                        } catch (error) {
                            vc.toast('保存失败，请重试');
                            return;
                        }
                        if (_json.code == 0) {
                            //关闭model
                            $('#addOwnerModel').modal('hide');
                            vc.component.clearAddOwnerInfo();
                            vc.emit($props.notifyLoadDataComponentName, 'listOwnerData', {});

                            return;
                        }
                        vc.toast(_json.msg);

                    },
                    function (errInfo, error) {
                        info.saving = false;
                        console.log('请求失败处理');

                        vc.toast(errInfo);

                    });
            },
            clearAddOwnerInfo: function () {
                if (vc.component.addOwnerInfo.saving) {
                    return;
                }
                vc.component._closeVedio();
                let _componentTitle = $that.addOwnerInfo.componentTitle;
                vc.component.addOwnerInfo = {
                    componentTitle: _componentTitle,
                    name: '',
                    age: '',
                    link: '',
                    sex: '',
                    ownerTypeCd: '-1',
                    remark: '',
                    ownerId: '',
                    ownerPhoto: '',
                    idCard: '',
                    videoPlaying: true,
                    mediaStreamTrack: null,
                    saving: false,
                    selectedRooms: [],
                    roomOptions: [],
                    roomFloorNum: '',
                    roomNum: '',
                    roomPage: 1,
                    roomPages: 0,
                    roomRecords: 0,
                    roomLoading: false,
                    roomRequestId: 0,
                    roomCommunityId: vc.getCurrentCommunity().communityId,
                    attrs:[]
                };
                this._loadOwnerAttrSpec();
            },
            _addUserMedia: function () {
                return navigator.getUserMedia = navigator.getUserMedia ||
                    navigator.webkitGetUserMedia ||
                    navigator.mozGetUserMedia ||
                    navigator.msGetUserMedia || null;
            },
            _initAddOwnerMedia: function () {
                if (vc.component._addUserMedia()) {
                    var constraints = {
                        video: true,
                        audio: false
                    };
                    var video = document.getElementById('ownerPhoto');
                    var media = navigator.getUserMedia(constraints, function (stream) {
                        var url = window.URL || window.webkitURL;
                        $that.addOwnerInfo.mediaStreamTrack = typeof stream.stop === 'function' ? stream : stream.getTracks()[0];
                        try {
                            video.src = url ? url.createObjectURL(stream) : stream;
                        } catch (error) {
                            video.srcObject = stream;
                        }
                        video.play();
                        vc.component.addOwnerInfo.videoPlaying = true;
                    }, function (error) {
                        vc.component.addOwnerInfo.videoPlaying = false;
                        console.log("ERROR");
                        console.log(error);
                    });
                } else {
                    vc.component.addOwnerInfo.videoPlaying = false;
                    console.log("初始化视频失败");
                }
            },
            _takePhoto: function () {
                if (vc.component.addOwnerInfo.videoPlaying) {
                    var canvas = document.getElementById('canvas');
                    var video = document.getElementById('ownerPhoto');
                    canvas.width = video.videoWidth;
                    canvas.height = video.videoHeight;
                    canvas.getContext('2d').drawImage(video, 0, 0);
                    var data = canvas.toDataURL('image/jpeg', 1.0);
                    vc.component.addOwnerInfo.ownerPhoto = data;
                    //document.getElementById('photo').setAttribute('src', data);
                    //关闭拍照摄像头
                    $that._closeVedio();
                } else {
                    vc.toast('未检测到摄像头');
                }
            },
            _uploadPhoto: function (event) {
                console.log('上传图片');
                //vc.component.addOwnerInfo.ownerPhoto = "";
                $("#uploadOwnerPhoto").trigger("click")
            },
            _choosePhoto: function (event) {
                var photoFiles = event.target.files;
                if (photoFiles && photoFiles.length > 0) {
                    // 获取目前上传的文件
                    var file = photoFiles[0];// 文件大小校验的动作
                    if (file.size > 1024 * 1024 * 1) {
                        vc.toast("图片大小不能超过 1MB!")
                        return false;
                    }
                    var reader = new FileReader(); //新建FileReader对象
                    reader.readAsDataURL(file); //读取为base64
                    reader.onloadend = function (e) {
                        vc.component.addOwnerInfo.ownerPhoto = reader.result;
                    }
                }
            },
            _reOpenVedio: function () {
                vc.component.addOwnerInfo.ownerPhoto = "";
                vc.component._initAddOwnerMedia();
            },
            _closeVedio: function () {
                if (vc.component.addOwnerInfo.mediaStreamTrack != null) {
                    vc.component.addOwnerInfo.mediaStreamTrack.stop();
                    vc.component.addOwnerInfo.mediaStreamTrack = null;
                }
            },
            _loadOwnerAttrSpec: function () {
                $that.addOwnerInfo.attrs = [];
                vc.getAttrSpec('building_owner_attr', function (data) {
                    data.forEach(item => {
                        item.value = '';
                        if (item.specShow == 'Y') {
                            item.values = [];
                            $that._loadAttrValue(item.specCd, item.values);
                            $that.addOwnerInfo.attrs.push(item);
                        }
                    });

                });
            },
            _loadAttrValue: function (_specCd, _values) {
                vc.getAttrValue(_specCd, function (data) {
                    data.forEach(item => {
                        if (item.valueShow == 'Y') {
                            _values.push(item);
                        }
                    });

                });
            },
        }
    });

})(window.vc);
