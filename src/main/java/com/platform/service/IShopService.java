package com.platform.service;

import com.platform.dto.Result;
import com.platform.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    Result queryById(Long id) ;

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    Result update(Shop shop);

    //Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
