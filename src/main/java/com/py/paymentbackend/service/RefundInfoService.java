package com.py.paymentbackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.py.paymentbackend.entity.RefundInfo;

import java.util.List;


public interface RefundInfoService extends IService<RefundInfo> {

    RefundInfo createRefundByOrderNo(String orderNo, String reason, String paymentType);

    void updateRefund(String content);

    List<RefundInfo> getNoRefundOrderByDuration(int i, String payType);

    void updateRefundForAlipay(String refundNo, String body, String status);


}
