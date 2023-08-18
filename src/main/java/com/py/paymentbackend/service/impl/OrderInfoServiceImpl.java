package com.py.paymentbackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.py.paymentbackend.entity.OrderInfo;
import com.py.paymentbackend.entity.Product;
import com.py.paymentbackend.enums.OrderStatus;
import com.py.paymentbackend.mapper.OrderInfoMapper;
import com.py.paymentbackend.mapper.ProductMapper;
import com.py.paymentbackend.service.OrderInfoService;
import com.py.paymentbackend.util.OrderNoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;


@Slf4j
@Service
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    @Resource
    private ProductMapper productMapper;

    @Override
    public OrderInfo createOrderByProductId(Long productId, String paymentType) {
        // 1.查找已存在但未支付的订单  这里没有用用户去做区分，这里仅用商品
        OrderInfo orderInfo = this.getNoPayOrderByProductId(productId, paymentType);
        if (Objects.nonNull(orderInfo)) {
            log.info("存在未支付的订单，订单id：{}", orderInfo.getId());
            return orderInfo;
        }

        // 2.获取商品信息
        Product product = productMapper.selectById(productId);

        // 3.生成订单
        orderInfo = new OrderInfo();
        orderInfo.setTitle(product.getTitle());
        orderInfo.setPaymentType(paymentType);
        orderInfo.setOrderNo(OrderNoUtils.getOrderNo());
        orderInfo.setProductId(productId);
        orderInfo.setTotalFee(product.getPrice());
        orderInfo.setOrderStatus(OrderStatus.NOTPAY.getType());
        baseMapper.insert(orderInfo);
        log.info("返回的订单id：{}", orderInfo.getId());

        return orderInfo;
    }

    /**
     * 查找已存在但未支付的订单,防止重复创建订单对象
     */
    private OrderInfo getNoPayOrderByProductId(Long productId, String paymentType) {
        QueryWrapper<OrderInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("product_id", productId);
        queryWrapper.eq("order_status", OrderStatus.NOTPAY.getType());
        queryWrapper.eq("payment_type", paymentType);
        //queryWrapper.eq("user_id", userId);
        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 下单成功之后才能去缓存二维码，保存订单的时候还没有二维码
     */
    @Override
    public void saveCodeUrl(String orderNo, String codeUrl) {
        QueryWrapper<OrderInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("order_no", orderNo);
        OrderInfo orderInfo = baseMapper.selectOne(queryWrapper);
        orderInfo.setCodeUrl(codeUrl);
        baseMapper.updateById(orderInfo);
    }

    /**
     * 查询订单列表并按照创建时间降序返回
     * 按理来说应该根据用户信息去获取的，这里简化了
     */
    @Override
    public List<OrderInfo> listOrderByCreateTimeDesc() {
        QueryWrapper<OrderInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("create_time");
        return baseMapper.selectList(queryWrapper);
    }
}
