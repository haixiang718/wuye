import localFeatures from '../../config/local-features.js';
/**
 * 首页相关 数据封装API
 * add by 吴学文 2020-09-07
 * QQ 92825595
 * 
 */

import {
	requestNoAuth
} from '../java110Request.js';
import url from '../../constant/url.js';


import {
	dateTimeStringToDateString
} from '../../utils/DateUtil.js'

/**
 * 查询活动列表
 */
export function getActivitiTitle(dataObj) {

	return new Promise(
		(resolve, reject) => {
			requestNoAuth({
				url: url.queryActivitiesType,
				method: "GET",
				data: dataObj,
				//动态数据
				success: function(res) {
					if (res.statusCode == 200) {
						let _activites = res.data.data;
						resolve(_activites);
						return;
					}
					reject();
				},
				fail: function(e) {
					reject();
				}
			});
		})
}

/**
 * 查询 目录列表
 */
export function getCategoryList() {
    return localFeatures.filterMenuPages({pageone: [
        {name: "物业费",src: "/static/images/index_property.png",href: "/pages/roomFeeListNew/roomFeeListNew"},
        {name: "停车费",src: "/static/images/index_park.png",href: "/pages/payParkingFeeList/payParkingFeeList"},
        {name: "临时车收费",src: "/static/images/index_openDoor.png",href: "/pages/tempParkingFee/tempParkingFee"}
    ]});
}

/**
 * 查询活动
 */
export function loadActivites(dataObj) {
	return new Promise(
		(resolve, reject) => {
			requestNoAuth({
				url: url.listActivitiess,
				method: "GET",
				data: dataObj,
				//动态数据
				success: function(res) {
					if (res.statusCode == 200) {
						let _activites = res.data.activitiess;
						let _acts = [];
						_activites.forEach(function(_item) {
							_item.src = url.filePath + "?fileId=" + _item.headerImg + "&communityId=" + dataObj.communityId +
								"&time=" + new Date();
							_item.startTime = dateTimeStringToDateString(_item.startTime);

							_acts.push(_item);
						});
						resolve(_acts);
						return;
					}
					reject();
				},
				fail: function(e) {
					reject();
				}
			});
		})
}

/**
 * 查询小区广告
 */
export function loadAdverts(dataObj) {
	return new Promise(
		(resolve, reject) => {
			requestNoAuth({
				url: url.listAdvertPhoto,
				method: "GET",
				data: dataObj,
				//动态数据
				success: function(res) {
					if (res.statusCode == 200) {
						let _advertPhotos = res.data;
						resolve(_advertPhotos);
						return;
					}
					reject();
				},
				fail: function(e) {
					reject();
				}
			});
		})
}
