package com.activespace.service;

import com.activespace.dto.Result;
import com.activespace.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询店铺列表信息
     * @return
     */
    Result queryTypeList();
}
