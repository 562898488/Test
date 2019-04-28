package com.vz.finance.bankorg.hebeibank.controller;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.vz.finance.bankorg.hebeibank.constant.SystemConstants;
import com.vz.finance.bankorg.hebeibank.dao.OrderInfoDao;
import com.vz.finance.bankorg.hebeibank.mq.dto.message.FeedbackInfo;
import com.vz.finance.bankorg.hebeibank.mq.producer.BankProducer;
import com.vz.finance.bankorg.hebeibank.service.PreLoanService;
import com.vz.finance.bankorg.utils.ConvertUtil;
import com.vzoom.utils.EmptyUtils;

/**
 * 微众平台调用接口
 * 
 * @author zb
 *
 */
@Controller
@RequestMapping("/service")
public class VzoomController {

	private Logger logger = LogManager.getLogger(VzoomController.class.getName());

	@Autowired
	private OrderInfoDao orderInfoDao;

	@Autowired
	private PreLoanService preLoanService;

	@Autowired
	private BankProducer bankProducer;

	// 授信订单反馈信息接收
	@ResponseBody
	@RequestMapping(value = "/creditCall.do", produces = { "application/xml;charset=utf-8" },method = { RequestMethod.POST, RequestMethod.GET })
	public String preLoanFromVzoom(@RequestBody String requestXml, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		logger.info("======授信订单反馈信息接收，开始执行======");
		logger.info("接收请求方报文为：" + requestXml);
		if (StringUtils.isBlank(requestXml)) {
			return getResponseStr(SystemConstants.RESPONSE_SECONDE);
		}
		// 请求xml转化为map
		Map requestMap1 = ConvertUtil.commonXmlToMap(requestXml);
		Map map1 = (Map)requestMap1.get("request");
		List requestList = (List)map1.get("item");

		new Thread(new Runnable() {
			@Override
			public void run() {
				// 授信订单数据入库
				for (Object object : requestList) {
					Map requestMap = (Map)object;
					try {
						// 产品名称赋值
						String productName = new String(SystemConstants.PRODUCT_NAME.getBytes(), "UTF-8");
						Map<String, String> map = new HashMap<String, String>();
						map.put("ID", UUID.randomUUID().toString());
						map.put("PRODUCT_CODE", SystemConstants.PRODUCT_ID);
						map.put("PRODUCT_NAME", productName);
						map.put("NSRSBH", EmptyUtils.isNotEmpty(requestMap.get("taxNum")) ? requestMap.get("taxNum").toString() : ""); // 纳税人识别号
						map.put("QYMC", EmptyUtils.isNotEmpty(requestMap.get("companyName")) ? requestMap.get("companyName").toString() : ""); // 企业名称
						map.put("SQ_SJ", EmptyUtils.isNotEmpty(requestMap.get("applyTime")) ? requestMap.get("applyTime").toString() : ""); // 申请时间
						map.put("SP_SJ", EmptyUtils.isNotEmpty(requestMap.get("approveTime")) ? requestMap.get("approveTime").toString() : ""); // 审批时间
						map.put("SP_JG", getApproveStatus(EmptyUtils.isNotEmpty(requestMap.get("approveStatus")) ? requestMap.get("approveStatus").toString() : "")); // 审批状态
						map.put("SX_JE", EmptyUtils.isNotEmpty(requestMap.get("creditAmount")) ? requestMap.get("creditAmount").toString() : ""); // 授信金额
						map.put("SX_QX", EmptyUtils.isNotEmpty(requestMap.get("timeLimit")) ? requestMap.get("timeLimit").toString() : ""); // 授信期限===
						map.put("RATE", EmptyUtils.isNotEmpty(requestMap.get("rate")) ? requestMap.get("rate").toString() : ""); // 年化利率===
						map.put("SX_KSSJ", EmptyUtils.isNotEmpty(requestMap.get("creditStartTime")) ? requestMap.get("creditStartTime").toString() : ""); // 授信开始时间
						map.put("SX_JSSJ", EmptyUtils.isNotEmpty(requestMap.get("creditEndTime")) ? requestMap.get("creditEndTime").toString() : ""); // 授信结束时间
						map.put("REMARK", EmptyUtils.isNotEmpty(requestMap.get("approveMsg")) ? requestMap.get("approveMsg").toString() : ""); // 审批说明	==
						map.put("ZH_STATUS", EmptyUtils.isNotEmpty(requestMap.get("acountStatus")) ? requestMap.get("acountStatus").toString() : ""); // 账户状态
						map.put("orderStatus", getApproveStatusNum(EmptyUtils.isNotEmpty(requestMap.get("approveStatus")) ? requestMap.get("approveStatus").toString() : "")); // 反馈服务需要

						int orderSize = orderInfoDao.getOrderFeedback(map).size();
						logger.info("查询纳税号是否存在，size=" + orderSize);
						if (orderSize > 0) {
							logger.info("======修改纳税号" + requestMap.get("taxNum") + "授信订单反馈数据======");
							orderInfoDao.updateOrderFeedback(map);
						} else {
							logger.info("======插入纳税号" + requestMap.get("taxNum") + "授信订单反馈数据======");
							orderInfoDao.insertOrderFeedback(map);
						}
						// 反馈服务
						bankProducer.pushOrderFeedToOrderServer(FeedbackInfo.valueOf(map));
					} catch (Exception e) {
						logger.error("银行订单状态反馈异常，识别号：" + requestMap.get("taxNum"), e);

					}
				}

			}
		}).start();
		
		// 返回响应给银行
		return getResponseStr(SystemConstants.RESPONSE_FIRST);
	}
	
	private String getResponseStr(String type){
		if(SystemConstants.RESPONSE_FIRST.equals(type)){
			return "<transaction><returnCode>00000000</returnCode><returnMessage>成功</returnMessage></transaction>";
		}else if(SystemConstants.RESPONSE_SECONDE.equals(type)){
			return "<transaction><returnCode>90000000</returnCode><returnMessage>发送请求为空</returnMessage></transaction>";
		}else{
			return "<transaction><returnCode>90000001</returnCode><returnMessage>未知错误</returnMessage></transaction>";
		}
	}

	/**
	 * 根据状态返回对应的状态名称
	 * 
	 * @param statusNumber
	 * @return
	 */
	private String getApproveStatus(String statusNumber) {
		String returnStatus = "订单关闭";
		switch (statusNumber) {
		case "001":
			returnStatus = " 审批中";
			break;
		case "002":
			returnStatus = "审批通过";
			break;
		case "003":
			returnStatus = "审批拒绝";
			break;
		case "004":
			returnStatus = "订单关闭";
			break;
		default:
			returnStatus = "订单关闭";
			break;
		}
		return returnStatus;
	}

	/**
	 * 根据状态返回对应的状态码
	 * 
	 * @param statusNumber
	 * @return
	 */
	private String getApproveStatusNum(String statusNumber) {
		String returnStatus = "9999";
		switch (statusNumber) {
		case "001":
			returnStatus = "2000";
			break;
		case "002":
			returnStatus = "0000";
			break;
		case "003":
		case "004":
			returnStatus = "9999";
			break;
		default:
			returnStatus = "9999";
			break;
		}
		return returnStatus;
	}

}
