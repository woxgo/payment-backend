package com.py.paymentbackend.service.impl;

import com.google.gson.Gson;
import com.py.paymentbackend.config.WxPayConfig;
import com.py.paymentbackend.entity.OrderInfo;
import com.py.paymentbackend.enums.OrderStatus;
import com.py.paymentbackend.enums.PayType;
import com.py.paymentbackend.enums.wxpay.WxApiType;
import com.py.paymentbackend.enums.wxpay.WxNotifyType;
import com.py.paymentbackend.service.OrderInfoService;
import com.py.paymentbackend.service.PaymentInfoService;
import com.py.paymentbackend.service.WxPayService;
import com.wechat.pay.contrib.apache.httpclient.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author yangjiewei
 * @date 2022/8/24
 */
@Service
@Slf4j
public class WxPayServiceImpl implements WxPayService {

    /**
     * 获取微信支付的配置信息
     */
    @Resource
    private WxPayConfig wxPayConfig;

    @Resource
    private OrderInfoService orderInfoService;


    /**
     * 获取微信支付的httpClient，可以签名验签
     */
    @Resource
    private CloseableHttpClient wxPayClient;

    @Resource
    private PaymentInfoService paymentInfoService;

    private final ReentrantLock lock = new ReentrantLock();


    /**
     * 开发指引：https://pay.weixin.qq.com/wiki/doc/apiv3/open/pay/chapter2_7_2.shtml
     * 接口文档：https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter3_4_1.shtml
     * requestJson
     * {
     *   "amount":{
     *       "total":1,
     *       "currency":"CNY"
     *   },
     *   "mchid":"1558950191",
     *   "out_trade_no":"ORDER_20220825104830065",
     *   "appid":"wx74862e0dfcf69954",
     *   "description":"test",
     *   "notify_url":"https://500c-219-143-130-12.ngrok.io/api/wx-pay/native/notify"
     * }
     *
     * response
     * {
     *   "code": 0,
     *   "message": "成功",
     *   "data": {
     *     "codeUrl": "weixin://wxpay/bizpayurl?pr=tyq42wrzz",
     *     "orderNo": "ORDER_20220825104830065"
     *   }
     * }
     */
    @Override
    public Map<String, Object> nativePay(Long productId) throws Exception {
        log.info("1.生成订单");

        String codeUrl;
        OrderInfo orderInfo = orderInfoService.createOrderByProductId(productId, PayType.WXPAY.getType());
        if (Objects.nonNull(orderInfo ) && !StringUtils.isEmpty(orderInfo.getCodeUrl())) {
            log.info("订单已存在，二维码已保存");
            log.info("订单号:{}，二维码:{}", orderInfo.getOrderNo(), orderInfo.getCodeUrl());
            codeUrl = orderInfo.getCodeUrl();
            // 返回二维码
            Map<String, Object> map = new HashMap<>();
            map.put("codeUrl", codeUrl);
            map.put("orderNo", orderInfo.getOrderNo());
            return map;
        }

        log.info("2.调用统一下单api");

        // 创建post请求
        HttpPost httpPost = new HttpPost(wxPayConfig.getDomain().concat(WxApiType.NATIVE_PAY.getType()));

        // 构造请求参数
        // 这里请求参数很多，只传必填项就可以了，请求和响应都是json格式
        // gson是处理json的
        Gson gson = new Gson();
        // 你怎么知道要这些参数，参考文档啊 https://pay.weixin.qq.com/wiki/doc/apiv3_partner/apis/chapter4_4_1.shtml
        Map paramsMap = new HashMap();
        paramsMap.put("appid", wxPayConfig.getAppid());
        paramsMap.put("mchid", wxPayConfig.getMchId());
        paramsMap.put("description", orderInfo.getTitle());
        paramsMap.put("out_trade_no", orderInfo.getOrderNo());
        paramsMap.put("notify_url", wxPayConfig.getNotifyDomain().concat(WxNotifyType.NATIVE_NOTIFY.getType()));
        // 订单金额对象
        Map amountMap = new HashMap();
        amountMap.put("total", orderInfo.getTotalFee());
        amountMap.put("currency", "CNY");

        paramsMap.put("amount", amountMap);

        // 将参数转化成json字符串
        String requestJson = gson.toJson(paramsMap);
        log.info("3.构造请求参数");
        log.info("请求参数：{}", requestJson);

        // 设置请求体及请求头
        StringEntity entity = new StringEntity(requestJson, "utf-8");
        entity.setContentType("application/json");
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");

        // 完成签名并执行请求 wxPayClient会自动的处理签名和验签，并进行证书自动更新
        CloseableHttpResponse nativePayResponse = wxPayClient.execute(httpPost);
        log.info("4.解析微信native下单响应");
        try{
            // 获取响应体并转为字符串和响应状态码
            String response = EntityUtils.toString(nativePayResponse.getEntity());
            int statusCode = nativePayResponse.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                // 处理成功
                log.info("成功, 返回结果 = " + response);
            } else if (statusCode == 204) {
                // 处理成功，无返回Body
                log.info("成功");
            } else {
                log.info("Native下单失败,响应码 = " + statusCode+ ",返回结果 = " + response);
                throw new IOException("request failed");
            }
            // 响应结果 json字符串转对象
            Map<String, String> resultMap = gson.fromJson(response, HashMap.class);
            // 获取二维码并保存
            codeUrl = resultMap.get("code_url");
            String orderNo = orderInfo.getOrderNo();
            orderInfoService.saveCodeUrl(orderNo, codeUrl);

            Map<String, Object> map = new HashMap<>();
            map.put("codeUrl", codeUrl);
            map.put("orderNo", orderNo);
            log.info("5.响应二维码：{}，订单号：{}", codeUrl, orderNo);
            return map;
        }finally {
            nativePayResponse.close();
        }
    }


    /**
     * 处理订单
     * @param bodyMap 支付通知参数
     * @throws GeneralSecurityException
     */
    @Override
    public void processOrder(Map<String, Object> bodyMap) throws GeneralSecurityException {
        log.info("处理订单");

        // 1.密文解密
        String plainText = decryptFromResource(bodyMap);

        // 2.转换明文 https://pay.weixin.qq.com/wiki/doc/apiv3_partner/apis/chapter4_4_5.shtml
        Gson gson = new Gson();
        Map<String, Object> plainTextMap = gson.fromJson(plainText, HashMap.class);
        String orderNo = (String) plainTextMap.get("out_trade_no");

        /**
         * 在对业务数据进行状态检查和处理之前，这里要使用数据锁进行并发控制，以避免函数重入导致的数据混乱
         * 尝试获取锁成功之后才去处理数据，相比于同步锁，这里不会去等待，获取不到则直接返回
         */
        if (lock.tryLock()) {
            try {
                // 处理重复通知 出于接口幂等性考虑（无论接口被调用多少次，产生的结果都是一致的）
                String orderStatus = orderInfoService.getOrderStatus(orderNo);
                if (!OrderStatus.NOTPAY.getType().equals(orderStatus)) {
                    return ;
                }

/*                // 模拟通知并发 try catch快捷键是 ctrl+wins+alt+t
                // 虽然前面处理了重复通知，但是这里是并发导致，这里要使用数据锁进行并发控制，以避免函数重入导致的数据混乱
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }*/

                // 3.更新订单状态
                orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.SUCCESS);

                // 4.记录支付日志
                paymentInfoService.createPaymentInfo(plainText);
            } finally {
                // 要主动释放锁
                lock.unlock();
            }
        }

    }

    /**
     * 对称解密
     * 为了保证安全性，微信支付在回调通知和平台证书下载接口中，对关键信息进行了AES-256-GCM加密。
     * 证书和回调报文使用的加密密钥为APIv3密钥，32字节 https://wechatpay-api.gitbook.io/wechatpay-api-v3/ren-zheng/api-v3-mi-yao
     */
    private String decryptFromResource(Map<String, Object> bodyMap) throws GeneralSecurityException {
        log.info("密文解密");
        // 获取通知数据中的resource，这部分有加密数据
        Map<String, String> resourceMap = (Map) bodyMap.get("resource");
        // 数据密文
        String ciphertext = resourceMap.get("ciphertext");
        // 随机串
        String nonce = resourceMap.get("nonce");
        // 附加数据
        String associatedData = resourceMap.get("associated_data");

        log.info("密文数据：{}", ciphertext);

        // 用APIv3密钥去解密
        AesUtil aesUtil = new AesUtil(wxPayConfig.getApiV3Key().getBytes(StandardCharsets.UTF_8));

        // 使用封装好的工具类去解密
        String plainText = aesUtil.decryptToString(
                associatedData.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                ciphertext);

        log.info("明文：{}", plainText);
        return plainText;
    }

    /**
     * 用户取消订单
     */
    @Override
    public void cancelOrder(String orderNo) throws IOException {
        // 调用微信支付的关单接口
        this.closeOrder(orderNo);
        //更新商户端的订单状态
        orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.CANCEL);
    }

    /**
     * 关单接口调用
     * https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter3_1_3.shtml
     * 以下情况需要调用关单接口：
     *    1、商户订单支付失败需要生成新单号重新发起支付，要对原订单号调用关单，避免重复支付；
     *    2、系统下单后，用户支付超时，系统退出不再受理，避免用户继续，请调用关单接口。
     * @param orderNo
     */
    private void closeOrder(String orderNo) throws IOException {
        log.info("关单接口的调用，订单号：{}", orderNo);
        // 创建远程请求对象
        String url = String.format(WxApiType.CLOSE_ORDER_BY_NO.getType(), orderNo);
        url = wxPayConfig.getDomain().concat(url);
        HttpPost httpPost = new HttpPost(url);

        // 组装json请求体
        Gson gson = new Gson();
        Map<String, String> paramsMap = new HashMap<>();
        // todo 目前文档是有 服务商务号、子商户号，如果是 JSAPI则对得上
        paramsMap.put("mchid", wxPayConfig.getMchId());
        String jsonParams = gson.toJson(paramsMap);
        log.info("请求参数：{}", jsonParams);

        // 将请求参数设置到请求对象中
        StringEntity entity = new StringEntity(jsonParams,"utf-8");
        entity.setContentType("application/json");
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");

        // 完成签名并执行请求
        CloseableHttpResponse response = wxPayClient.execute(httpPost);

        try {
            int statusCode = response.getStatusLine().getStatusCode();
            // 响应状态码
            if (statusCode == 200) {
                // 处理成功
                log.info("成功200");
            } else if (statusCode == 204) {
                // 处理成功，无返回Body
                log.info("成功204");
            } else {
                log.info("Native下单失败,响应码 = " + statusCode);
                throw new IOException("request failed");
            }
        } finally {
            response.close();
        }
    }

    /**
     * https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter3_1_2.shtml
     * 文档上的path方法是指在url上的值，query则是参数
     * 查询订单调用
     */
    @Override
    public String queryOrder(String orderNo) throws IOException {
        log.info("查单接口调用：{}", orderNo);
        String url = String.format(WxApiType.ORDER_QUERY_BY_NO.getType(), orderNo);
        url = wxPayConfig.getDomain().concat(url).concat("?mchid=").concat(wxPayConfig.getMchId());

        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Accept", "application/json");

        // 完成签名并执行请求
        CloseableHttpResponse response = wxPayClient.execute(httpGet);

        try {
            String bodyAsString = EntityUtils.toString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                log.info("成功，结果是：{}", bodyAsString);
            }else if (statusCode == 204) {
                log.info("成功，无返回内容");
            }else {
                log.info("查询订单失败,响应码 = " + statusCode+ ",返回结果 = " +
                        bodyAsString);
                throw new IOException("queryOrder request failed");
            }
            return bodyAsString;
        } finally {
            response.close();
        }
    }

}