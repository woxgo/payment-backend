package com.py.paymentbackend.controller;

import com.google.gson.Gson;
import com.py.paymentbackend.util.HttpUtils;
import com.py.paymentbackend.service.WxPayService;
import com.py.paymentbackend.util.WechatPay2ValidatorForRequest;
import com.py.paymentbackend.vo.R;
import com.wechat.pay.contrib.apache.httpclient.auth.Verifier;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 要实现的接口有这些
 * https://pay.weixin.qq.com/wiki/doc/apiv3/open/pay/chapter2_7_3.shtml
 * @author yangjiewei
 * @date 2022/8/24
 */
@Slf4j
@CrossOrigin
@RestController
@Api(tags = "网站微信支付 native")
@RequestMapping("/api/wx-pay")
public class WxPayController {

    @Resource
    private WxPayService wxPayService;

    @Resource
    private Verifier verifier;



    /**
     * https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter3_4_1.shtml
     * native下单api
     * 根据商品信息获取费用信息等，这里没有实际获取商品，调用接口获取code_url，前端显示二维码
     * {
     *   "code": 0,
     *   "message": "成功",
     *   "data": {
     *     "codeUrl": "weixin://wxpay/bizpayurl?pr=JiLa01azz",
     *     "orderNo": "ORDER_20220824172547251"
     *   }
     * }
     */
    @ApiOperation("调用统一下单API，生成支付二维码")
    @PostMapping ("/native/{productId}")
    public R nativePay(@PathVariable Long productId) throws Exception {
        log.info("发起支付请求");
        // 返回支付二维码链接和订单号
        Map<String, Object> map = wxPayService.nativePay(productId);
        return R.ok().setData(map);
    }


    /**
     * 支付通知
     * 微信支付通过支付通知接口将用户支付成功消息通知给商户
     * https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter3_4_5.shtml
     * 这个接口对请求信息要做签名验证，避免假通知
     * 需要对该请求进行应答，成功或失败
     * {
     *    "code": "FAIL",
     *    "message": "失败"
     * }
     *
     * 微信发送过来的通知可能因为网络不稳定而出现网络超时，5S
     * 如果微信未能成功获取我们的响应，就会重复发送支付通知
     *
     */
    @ApiOperation("支付通知")
    @PostMapping("/native/notify")
    public String nativeNotify(HttpServletRequest request, HttpServletResponse response) {
        Gson gson = new Gson();
        // 构造应答对象
        Map<String, String> map = new HashMap<>();

        try{
            // 1.处理通知参数
            String body = HttpUtils.readData(request);
            Map<String, Object> bodyMap = gson.fromJson(body, HashMap.class);
            log.info("支付通知的id:{}", bodyMap.get("id"));
            log.info("支付通知的完整数据:{}", body);

            // 2.签名验证
            WechatPay2ValidatorForRequest validator
                    = new WechatPay2ValidatorForRequest(verifier, body, (String) bodyMap.get("id"));
            if (!validator.validate(request)) {
                log.error("通知验签失败");
                response.setStatus(500);
                map.put("code", "ERROR");
                map.put("message", "通知验签失败");
                return gson.toJson(map);
            }
            log.info("通知验签成功");

            // 3.处理订单 微信返回的通知数据是加密的
            wxPayService.processOrder(bodyMap);

            // 测试超时应答：添加睡眠时间使应答超时
            // 模拟超时，微信会重复请求，需要排除已处理过的订单
            // TimeUnit.SECONDS.sleep(5);
            response.setStatus(200);
            map.put("code", "SUCCESS");
            map.put("message", "成功");
            return gson.toJson(map);
        }catch (Exception e) {
            e.printStackTrace();
            // 测试错误应答
            response.setStatus(500);
            map.put("code", "ERROR");
            map.put("message", "系统错误");
            return gson.toJson(map);
        }

    }

    /**
     * 用户取消订单
     * @return
     */
    @ApiOperation("取消订单")
    @PostMapping("/cancel/{orderNo}")
    public R cancel(@PathVariable String orderNo) throws IOException {
        log.info("取消订单");
        wxPayService.cancelOrder(orderNo);
        return R.ok().setMessage("订单已取消");
    }

    /**
     * 微信支付查单API
     * https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter3_1_2.shtml
     * 商户可以通过查询订单接口主动查询订单状态，完成下一步的业务逻辑。查询订单状态可通过微信支付订单号或商户订单号两种方式查询
     * 通常商户后台未收到异步支付结果通知时，商户主动调用查单接口，同步订单状态。
     * {
     *   "code": 0,
     *   "message": "查询成功",
     *   "data": {
     *     "bodyAsString": "{\"amount\":{\"currency\":\"CNY\",\"payer_currency\":\"CNY\",\"payer_total\":1,\"total\":1},\"appid\":\"wx74862e0dfcf69954\",\"attach\":\"\",\"bank_type\":\"OTHERS\",\"mchid\":\"1558950191\",\"out_trade_no\":\"ORDER_20220828172344338\",\"payer\":{\"openid\":\"oHwsHuCgDFPyqFo2Sawg6yA0Pu4A\"},\"promotion_detail\":[],\"success_time\":\"2022-08-28T17:23:59+08:00\",\"trade_state\":\"SUCCESS\",\"trade_state_desc\":\"支付成功\",\"trade_type\":\"NATIVE\",\"transaction_id\":\"4200001550202208284738686219\"}"
     *   }
     * }
     */
    @ApiOperation("查询订单：测试订单状态用")
    @GetMapping("/query/{orderNo}")
    public R queryOrder(@PathVariable String orderNo) throws IOException {
        log.info("查询订单");
        String bodyAsString = wxPayService.queryOrder(orderNo);
        return R.ok().setMessage("查询成功").data("bodyAsString", bodyAsString);
    }


}