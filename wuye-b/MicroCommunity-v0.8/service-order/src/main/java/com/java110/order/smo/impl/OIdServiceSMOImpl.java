package com.java110.order.smo.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.java110.core.client.RestTemplate;
import com.java110.core.factory.GenerateCodeFactory;
import com.java110.dto.order.OrderDto;
import com.java110.dto.order.OrderItemDto;
import com.java110.order.dao.ICenterServiceDAO;
import com.java110.order.smo.IOIdServiceSMO;
import com.java110.utils.util.BeanConvertUtil;
import com.java110.utils.util.DateUtil;
import com.java110.utils.util.StringUtil;
import com.java110.utils.util.RollbackSqlLiteral;
import com.java110.vo.ResultVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 事务处理服务类
 * Created by wuxw on 2018/4/13.
 */
@Service("oIdServiceSMOImpl")
public class OIdServiceSMOImpl implements IOIdServiceSMO {

    private static Logger logger = LoggerFactory.getLogger(OIdServiceSMOImpl.class);


    public static final String FALLBACK_URL = "http://SERVICE_NAME/businessApi/fallBack";

    public static final String SERVICE_NAME = "SERVICE_NAME";


    @Autowired
    private ICenterServiceDAO centerServiceDAOImpl;

    @Autowired
    private RestTemplate restTemplate;


    @Override
    public ResponseEntity<String> createOId(OrderDto orderDto) {

        orderDto.setoId(GenerateCodeFactory.getOId());
        if (StringUtil.isEmpty(orderDto.getAppId())) {
            throw new IllegalArgumentException("未包含appId");
        }

        if (StringUtil.isEmpty(orderDto.getExtTransactionId())) {
            throw new IllegalArgumentException("未包含交互日志");
        }

        if (StringUtil.isEmpty(orderDto.getRequestTime())) {
            throw new IllegalArgumentException("未包含请求时间");
        }

        if (StringUtil.isEmpty(orderDto.getUserId())) {
            throw new IllegalArgumentException("未包含用户ID");
        }

        //保存订单信息
        centerServiceDAOImpl.saveOrder(BeanConvertUtil.beanCovertMap(orderDto));
        return new ResponseEntity<String>(JSONObject.toJSONString(orderDto), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<String> fallBackOId(OrderDto orderDto) {
        if (StringUtil.isEmpty(orderDto.getoId())) {
            throw new IllegalArgumentException("未包含事务ID");
        }

        //判断OID是否存在
        orderDto = BeanConvertUtil.covertBean(centerServiceDAOImpl.getOrder(BeanConvertUtil.beanCovertMap(orderDto)), OrderDto.class);

        if (orderDto == null) {
            return new ResponseEntity<String>("没有需要回退事务", HttpStatus.NOT_FOUND);
        }

        //查询 事务项
        Map orderItem = new HashMap();
        orderItem.put("oId", orderDto.getoId());
        List<Map> orderItemMaps = centerServiceDAOImpl.getOrderItems(orderItem);
        if (orderItemMaps == null || orderItemMaps.size() < 1) {
            return new ResponseEntity<String>("没有需要回退事务", HttpStatus.NOT_FOUND);
        }
        List<OrderItemDto> orderItemDtos = BeanConvertUtil.covertBeanList(orderItemMaps, OrderItemDto.class);

        HttpEntity<String> httpEntity = null;
        HttpHeaders header = new HttpHeaders();
        List<OrderItemDto> errorOrderItemDtos = new ArrayList<>();
        for (OrderItemDto orderItemDto : orderItemDtos) {
            try {
                JSONArray params = generateParam(orderItemDto);
                httpEntity = new HttpEntity<String>(params.toJSONString(), header);
                restTemplate.exchange(FALLBACK_URL.replace(SERVICE_NAME, orderItemDto.getServiceName()), HttpMethod.POST, httpEntity, String.class);

                //标记为订单项失败
                Map info = new HashMap();
                info.put("finishTime", DateUtil.getNow(DateUtil.DATE_FORMATE_STRING_A));
                info.put("statusCd", "E");
                info.put("bId", orderItemDto.getbId());
                info.put("oId", orderDto.getoId());
                centerServiceDAOImpl.updateOrderItem(info);

                //删除 事务日志
                //centerServiceDAOImpl.deleteUnItemLog(info);
            } catch (Exception e) {
                logger.error("回退事务失败", e);
                errorOrderItemDtos.add(orderItemDto);
            }
        }


        //标记为订单失败
        Map info = new HashMap();
        info.put("finishTime", DateUtil.getNow(DateUtil.DATE_FORMATE_STRING_A));
        info.put("statusCd", "E");
        info.put("oId", orderDto.getoId());
        centerServiceDAOImpl.updateOrder(info);

        if (errorOrderItemDtos.size() > 0) {
            return new ResponseEntity<String>(JSONArray.toJSONString(errorOrderItemDtos), HttpStatus.BAD_REQUEST);
        } else {
            return new ResponseEntity<String>("", HttpStatus.OK);
        }
    }

    /**
     * 生成回滚sql
     *
     * @param orderItemDto
     * @return
     */
    private JSONArray generateParam(OrderItemDto item) {
        String table = RollbackSqlLiteral.tableIdentifier(item.getActionObj());
        JSONObject log = JSONObject.parseObject(item.getLogText());
        JSONArray before = log.getJSONArray("preValue");
        JSONArray after = log.getJSONArray("afterValue");
        if (before == null || after == null) throw new IllegalArgumentException("Missing compensation row arrays");
        JSONArray result = new JSONArray();
        String action = item.getAction();
        if ("MOD".equals(action) && before.size() != after.size()) throw new IllegalArgumentException("Mismatched compensation rows");
        if (!"ADD".equals(action) && !"MOD".equals(action) && !"DEL".equals(action)) throw new IllegalArgumentException("Unknown compensation action");
        int count = "ADD".equals(action) ? after.size() : before.size();
        for (int i = 0; i < count; i++) {
            JSONObject previous = "ADD".equals(action) ? null : before.getJSONObject(i);
            JSONObject current = "DEL".equals(action) ? null : after.getJSONObject(i);
            StringBuilder sql = new StringBuilder();
            if ("DEL".equals(action)) {
                if (previous == null || previous.isEmpty()) throw new IllegalArgumentException("Empty compensation row");
                StringBuilder columns = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (String key : previous.keySet()) {
                    if (columns.length() > 0) { columns.append(','); values.append(','); }
                    columns.append(RollbackSqlLiteral.identifier(key));
                    values.append(RollbackSqlLiteral.fromLog(previous.get(key)));
                }
                sql.append("INSERT INTO ").append(table).append('(').append(columns).append(") VALUES (").append(values).append(')');
            } else {
                if (current == null || current.isEmpty()) throw new IllegalArgumentException("Missing compensation predicate");
                if ("MOD".equals(action)) {
                    if (previous == null || previous.isEmpty()) throw new IllegalArgumentException("Empty compensation row");
                    sql.append("UPDATE ").append(table).append(" SET ");
                    boolean first = true;
                    for (String key : previous.keySet()) {
                        if (!first) sql.append(','); first = false;
                        sql.append(RollbackSqlLiteral.identifier(key)).append('=').append(RollbackSqlLiteral.fromLog(previous.get(key)));
                    }
                } else {
                    sql.append("DELETE FROM ").append(table);
                }
                sql.append(" WHERE ");
                boolean first = true;
                for (String key : current.keySet()) {
                    if (!first) sql.append(" AND "); first = false;
                    sql.append(RollbackSqlLiteral.identifier(key)).append(" <=> ").append(RollbackSqlLiteral.fromLog(current.get(key)));
                }
                sql.append(" LIMIT 1");
            }
            JSONObject command = new JSONObject(); command.put("fallBackSql", sql.toString()); result.add(command);
        }
        return result;
    }

    @Override
    public ResponseEntity<String> createOrderItem(OrderItemDto orderItemDto) {

        if (StringUtil.isEmpty(orderItemDto.getoId())) {
            return new ResponseEntity<String>("请求报文中未包含事务ID", HttpStatus.NOT_FOUND);
        }

        if (StringUtil.isEmpty(orderItemDto.getAction())) {
            return new ResponseEntity<String>("请求报文中未包含动作", HttpStatus.NOT_FOUND);
        }

        if (StringUtil.isEmpty(orderItemDto.getActionObj())) {
            return new ResponseEntity<String>("请求报文中未包含动作对象", HttpStatus.NOT_FOUND);
        }

        if (StringUtil.isEmpty(orderItemDto.getServiceName())) {
            return new ResponseEntity<String>("请求报文中未包含服务", HttpStatus.NOT_FOUND);
        }
        if (StringUtil.isEmpty(orderItemDto.getLogText())) {
            return new ResponseEntity<String>("请求报文中未包含回滚日志", HttpStatus.NOT_FOUND);
        }
        if (StringUtil.isEmpty(orderItemDto.getbId()) || orderItemDto.getbId().startsWith("-")) {
            orderItemDto.setbId(GenerateCodeFactory.getBId());
        }
        //判断OID是否存在
        OrderDto orderDto = BeanConvertUtil.covertBean(centerServiceDAOImpl.getOrder(BeanConvertUtil.beanCovertMap(orderItemDto)), OrderDto.class);

        if (orderDto == null || "E".equals(orderDto.getStatusCd())) {
            return new ResponseEntity<String>("当前没有事务或者事务已经回滚", HttpStatus.NOT_FOUND);
        }
        centerServiceDAOImpl.saveOrderItem(BeanConvertUtil.beanCovertMap(orderItemDto));

        return ResultVo.createResponseEntity(ResultVo.CODE_OK, ResultVo.MSG_OK);
    }

    /**
     * 完成事务
     *
     * @param orderDto
     * @return
     */
    @Override
    public ResponseEntity<String> finishOrder(OrderDto orderDto) {
        if (StringUtil.isEmpty(orderDto.getoId())) {
            return new ResponseEntity<String>("请求报文中未包含事务ID", HttpStatus.NOT_FOUND);
        }

        //完成订单项
        Map info = new HashMap();
        info.put("finishTime", DateUtil.getNow(DateUtil.DATE_FORMATE_STRING_A));
        info.put("statusCd", "C");
        info.put("oId", orderDto.getoId());
        centerServiceDAOImpl.updateOrderItem(info);

        //删除 事务日志
        //centerServiceDAOImpl.deleteUnItemLog(info);

        //完成订单
        info = new HashMap();
        info.put("finishTime", DateUtil.getNow(DateUtil.DATE_FORMATE_STRING_A));
        info.put("statusCd", "C");
        info.put("oId", orderDto.getoId());
        centerServiceDAOImpl.updateOrder(info);

        return ResultVo.createResponseEntity(ResultVo.CODE_OK, ResultVo.MSG_OK);
    }
}
