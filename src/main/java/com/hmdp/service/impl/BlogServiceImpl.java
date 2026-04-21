package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {


    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }



    /**
     * 查看博客
     * @param id
     * @return
     */
    public Result queryBlogById(Long id) {
        // 查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询blog相关的用户
        queryBlogUser(blog);

        //查询blog是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    /**
     * 点赞功能
     * @param id
     * @return
     */
    public Result likeBlog(Long id) {
        //1.获取登入用户
        Long userId = UserHolder.getUser().getId();

        //2.判断当前用户是否已点赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, String.valueOf(userId));

        //3.不存在=未点赞
        //3.1可以点赞,数据库点赞数+1
        if (score == null) {
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2保存用户到set集合
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.已点赞,取消点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2把用户在set集合上移除
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查看点赞排行榜前五人
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询 top5 的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        // 2. 判断是否为空（增强健壮性）
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 3. 解析出其中的用户 id
        // 将 Set<String> 转换为 List<Long>，Long::valueOf 是核心转换动作
        List<Long> ids = top5.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

       /* //3.创建一个空的 List 盒子
        List<Long> ids = new ArrayList<>();
        // 遍历 Redis 查出来的那个字符串袋子
        for (String s : top5) {
            // 把字符串转成 Long 数字
            Long id = Long.valueOf(s);
            // 把数字丢进盒子
            ids.add(id);
        }*/


        // 4. 根据用户 id 查询用户
        // 注意：数据库的 IN 查询会打乱顺序，所以需要手动处理顺序或使用 order by field
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 5. 返回结果
        return Result.ok(userDTOS);
    }

    /**
     * 查询Blog相关用户
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查询blog是否点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        Long id = blog.getId();
        //获取登入用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        Long userId = user.getId();

        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, String.valueOf(userId));
        if (score != null) {
            blog.setIsLike(true);
        }
    }
}
