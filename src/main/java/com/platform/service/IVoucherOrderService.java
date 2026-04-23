package com.platform.service;

import com.platform.dto.Result;
import com.platform.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 优惠券秒杀下单
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId) throws InterruptedException;

    void createVoucherOrder(VoucherOrder voucherOrder);
}
