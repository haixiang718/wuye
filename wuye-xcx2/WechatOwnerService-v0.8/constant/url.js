import conf from '../conf/config.js'
const baseUrl = conf.baseUrl;
export default {
	baseUrl: baseUrl,
	wechatRefrashToken: baseUrl + "app/refreshToken", // 公众号刷新token
	loginOwnerByKey: baseUrl + "app/loginOwnerByKey", // 根据临时秘钥登录
	loginUrl: baseUrl + 'app/loginWx',
	getWxPhoto: baseUrl + 'app/getWxPhoto',
	loginOwnerUrl: baseUrl + 'app/loginOwner',
	areaUrl: baseUrl + "app/area.listAreas",
	GetNoticeListUrl: baseUrl + 'app/notice.listNotices', //公告接口
	saveOwner: baseUrl + 'app/owner.saveOwner', //家庭成员列表
	queryOwnerMembers: baseUrl + 'app/owner.queryOwnerMembers', //投诉建议列表
	queryRoomsByOwner: baseUrl + 'app/room.queryRoomsByOwner', //绑定业主
	appUserBindingOwner: baseUrl + 'app/owner.appUserBindingOwner', //查询绑定业主
	ownerRegiter: baseUrl + 'app/owner.ownerRegister', //业主注册
	ownerRegisterWxPhoto: baseUrl + 'app/owner.ownerRegisterWxPhoto', //业主注册
	queryAppUserBindingOwner: baseUrl + 'app/owner.listAppUserBindingOwners', //解绑业主
	appUserUnBindingOwner: baseUrl + 'app/owner.deleteAppUserBindingOwner', //查询业主门禁
	uploadOwnerPhoto: baseUrl + "app/owner.uploadOwnerPhoto",
	getOwnerPhotoPath: baseUrl + "callComponent/download/getFile/fileByObjId",
	filePath: baseUrl + "callComponent/download/getFile/file", //查询业主车位信息
	queryParkingSpacesByOwner: baseUrl + "app/parkingSpace.queryParkingSpacesByOwner", //查询停车位费用
	queryFeeByParkingSpace: baseUrl + "app/fee.queryFeeByParkingSpace", //查询物业费用
	queryFeeByOwner: baseUrl + "app/fee.listFee", //预下单
	preOrder: baseUrl + "app/payment/toPay", //查询小区
	//临时车缴费
	preOrderTempCarInout: baseUrl + "/app/payment/toPayTempCarInout",
	listCommunitys: baseUrl + "app/community.listCommunitys", //查询小区文化
	listActivitiess: baseUrl + "app/activities.listActivitiess", //查询小区广告
	listAdvertPhoto: baseUrl + "app/advert.listAdvertPhoto",
	//查询车辆进场费用
	queryFeeByCarInout: baseUrl + "app/fee.queryFeeByCarInout",
	//查询报修单
	//查询在场车辆
	listCarIn: baseUrl + "app/carInout.listCarInouts",
	queryConfigFee: baseUrl + "app/fee.queryFeeConfig",
	//缴费历史查询
	queryFeeDetail: baseUrl + "app/fee.queryFeeDetail",
	//发送验证码
	userSendSms: baseUrl + "app/user.userSendSms",
	userLogout: baseUrl + 'app/user.service.logout', //绑定业主
	listStore: baseUrl + 'app/store.listStoresByCommunity', // 查询小区商户
	//发布需求信息
	//查询需求信息
	//删除 需求信息
	//标记为已完成
	//查询楼栋
	queryFloor: baseUrl + 'app/floor.queryFloors',
	//查询单元
	queryUnit: baseUrl + 'app/unit.queryUnits',
	//查询报修类型
	//待办工单
	//已办工单
	// 查询报修单处理师傅
	// 删除报修单
	// 报修退单
	// 报修评价
	// 根据属性查询费用
	listFeeByAttr: baseUrl + "app/feeApi/listFeeByAttr",
	// 投诉 处理意见
	listWorkflowAuditInfo: baseUrl + 'app/workflow.listWorkflowAuditInfo',
	//查询待审核单
	changeStaffPwd: baseUrl + 'app/user.changeStaffPwd', //修改密码
	changeOwnerPhone: baseUrl + 'app/ownerApi/changeOwnerPhone', //修改密码
	listOweFees: baseUrl + 'app/feeApi/listOweFees', //查询房屋欠费
	toOweFeePay: baseUrl + "app/payment/toOweFeePay", //欠费缴费
	queryActivitiesType: baseUrl + 'app/activitiesType/queryActivitiesType',
	deleteOwner: baseUrl + "app/owner.deleteOwner",
	computeFeeDiscount: baseUrl + "app/feeDiscount/computeFeeDiscount",
	queryFeeDiscount: baseUrl + "app/feeDiscount/queryFeeDiscount",
	// 查询申请类型（空置房）
	queryApplyRoomDiscountType: baseUrl + "app/applyRoomDiscount/queryApplyRoomDiscountType",
	saveApplyRoomDiscount: baseUrl + "app/applyRoomDiscount/saveApplyRoomDiscount",
	queryOwnerCars: baseUrl + "app/owner.queryOwnerCars",
	saveRoomRenovation: baseUrl + "app/roomRenovation/saveRoomRenovation",
	queryRoomRenovation: baseUrl + "app/roomRenovation/queryRoomRenovation",
	updateRoomRenovationState: baseUrl + "app/roomRenovation/updateRoomRenovationState",
	queryRoomRenovationRecord: baseUrl + "app/roomRenovation/queryRoomRenovationRecord",
	queryRoomRenovationRecordDetail: baseUrl + "app/roomRenovation/queryRoomRenovationRecordDetail",
	queryContract: baseUrl + "app/contract/queryContract",
	queryQuestionAnswer: baseUrl + "app/questionAnswer/queryQuestionAnswer",
	queryQuestionAnswerTitle: baseUrl + "app/questionAnswer/queryQuestionAnswerTitle",
	saveUserQuestionAnswerValue: baseUrl + "app/userQuestionAnswer/saveUserQuestionAnswerValue",
	generatorHcCode: baseUrl + "app/userLogin/generatorHcCode",

	NEED_NOT_LOGIN_PAGE: [
		'/pages/login/login',
		'/pages/register/register',
		'/pages/my/my',
		'/pages/index/index',
		'/pages/showlogin/showlogin'
	],
	NEED_NOT_LOGIN_URL: [
		// this.listActivitiess,
		// this.listAdvertPhoto,
		// this.queryAppUserBindingOwner,
		// this.listJunkRequirements
	]
}
