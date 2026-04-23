package com.platform.service;

import com.platform.dto.Result;
import com.platform.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 尝试关注用户
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 是否关注用户
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 共同关注好友
     * @param followUserId
     * @return
     */
    Result followCommons(Long followUserId);
}
