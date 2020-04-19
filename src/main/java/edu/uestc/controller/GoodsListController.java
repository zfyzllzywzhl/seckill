package edu.uestc.controller;

import edu.uestc.controller.result.Result;
import edu.uestc.domain.SeckillUser;
import edu.uestc.redis.GoodsKeyPrefix;
import edu.uestc.redis.RedisService;
import edu.uestc.service.GoodsService;
import edu.uestc.service.SeckillUserService;
import edu.uestc.vo.GoodsDetailVo;
import edu.uestc.vo.GoodsVo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring5.view.ThymeleafViewResolver;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * 商品列表页控制器
 * <p>
 * modified-4: 实现页面级缓存
 */
@Controller
@RequestMapping("/goods")
public class GoodsListController {

    @Autowired
    SeckillUserService seckillUserService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    RedisService redisService;

    // 因为在redis缓存中不存页面缓存时需要手动渲染，所以注入一个视图解析器，自定义渲染（默认是由SpringBoot完成的）
    @Autowired
    ThymeleafViewResolver thymeleafViewResolver;

    @Autowired
    ApplicationContext applicationContext;

    /**
     * 获取MiaoshaUser对象，并将其传递到页面解析器
     * 从数据库中获取商品信息（包含秒杀信息）
     * <p>
     * c5: QPS: 1267, 用户数目5000，每个用户发起10次请求，共5000*10次请求
     * <p>
     * c5: 页面级缓存实现；从redis中取页面，如果没有则需要手动渲染页面，并且将渲染的页面存储在redis中供下一次访问时获取
     *
     * @param request
     * @param response
     * @param model    响应的资源文件
     * @param user     通过自定义参数解析器UserArgumentResolver解析的 SeckillUser 对象
     * @return
     */
    @RequestMapping(value = "/to_list", produces = "text/html")// produces表明：这个请求会返回text/html媒体类型的数据
    @ResponseBody
    public String toList(HttpServletRequest request,
                         HttpServletResponse response,
                         Model model,
                         SeckillUser user) {

        model.addAttribute("user", user);

        // 1. 从redis缓存中取html
        String html = redisService.get(GoodsKeyPrefix.goodsListKeyPrefix, "", String.class);
        if (!StringUtils.isEmpty(html))
            return html;

        // 2. 如果redis中不存在该缓存，则需要手动渲染

        // 查询商品列表，用于手动渲染时将商品数据填充到页面
        List<GoodsVo> goodsVoList = goodsService.listGoodsVo();
        model.addAttribute("goodsList", goodsVoList);

        // 3. 渲染html
        WebContext webContext = new WebContext(request, response, request.getServletContext(), request.getLocale(), model.asMap());
        // (第一个参数为渲染的html文件名，第二个为web上下文：里面封装了web应用的上下文)
        html = thymeleafViewResolver.getTemplateEngine().process("goods_list", webContext);

        if (!StringUtils.isEmpty(html)) // 如果html文件不为空，则将页面缓存在redis中
            redisService.set(GoodsKeyPrefix.goodsListKeyPrefix, "", html);

        return html;
    }

    /**
     * 处理商品详情页（未做页面静态化处理）
     * <p>
     * c5: URL级缓存实现；从redis中取商品详情页面，如果没有则需要手动渲染页面，并且将渲染的页面存储在redis中供下一次访问时获取
     * 实际上URL级缓存和页面级缓存是一样的，只不过URL级缓存会根据url的参数从redis中取不同的数据
     *
     * @param request
     * @param response
     * @param model    页面的域对象
     * @param user     用户信息
     * @param goodsId  商品id
     * @return 商品详情页
     */
    @RequestMapping(value = "/to_detail/{goodsId}", produces = "text/html")// 注意这种写法
    @ResponseBody
    public String toDetail(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            SeckillUser user,
            @PathVariable("goodsId") long goodsId) {

        // 1. 根据商品id从redis中取详情数据的缓存
        String html = redisService.get(GoodsKeyPrefix.goodsDetailKeyPrefix, "" + goodsId, String.class);
        if (!StringUtils.isEmpty(html)) {// 如果缓存中数据存在着直接返回
            return html;
        }

        // 2. 如果缓存中数据不存在，则需要手动渲染详情界面数据并返回
        model.addAttribute("user", user);
        // 通过商品id查询
        GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);
        model.addAttribute("goods", goods);

        // 获取商品的秒杀开始与结束的时间
        long startDate = goods.getStartDate().getTime();
        long endDate = goods.getEndDate().getTime();
        long now = System.currentTimeMillis();

        // 秒杀状态; 0: 秒杀未开始，1: 秒杀进行中，2: 秒杀已结束
        int miaoshaStatus = 0;
        // 秒杀剩余时间
        int remainSeconds = 0;

        if (now < startDate) { // 秒杀未开始
            miaoshaStatus = 0;
            remainSeconds = (int) ((startDate - now) / 1000);
        } else if (now > endDate) { // 秒杀已结束
            miaoshaStatus = 2;
            remainSeconds = -1;
        } else { // 秒杀进行中
            miaoshaStatus = 1;
            remainSeconds = 0;
        }

        // 将秒杀状态和秒杀剩余时间传递给页面（goods_detail）
        model.addAttribute("miaoshaStatus", miaoshaStatus);
        model.addAttribute("remainSeconds", remainSeconds);

//        return "goods_detail";

        // 3. 渲染html
        WebContext webContext = new WebContext(request, response, request.getServletContext(), request.getLocale(), model.asMap());
        // (第一个参数为渲染的html文件名，第二个为web上下文：里面封装了web应用的上下文)
        html = thymeleafViewResolver.getTemplateEngine().process("goods_detail", webContext);

        if (!StringUtils.isEmpty(html)) // 如果html文件不为空，则将页面缓存在redis中
            redisService.set(GoodsKeyPrefix.goodsDetailKeyPrefix, "" + goodsId, html);

        return html;
    }


    /**
     * * 处理商品详情页（页面静态化处理, 直接将数据返回给客户端，交给客户端处理）
     * <p>
     * c5: URL级缓存实现；从redis中取商品详情页面，如果没有则需要手动渲染页面，并且将渲染的页面存储在redis中供下一次访问时获取
     * 实际上URL级缓存和页面级缓存是一样的，只不过URL级缓存会根据url的参数从redis中取不同的数据
     *
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping(value = "/to_detail_static/{goodsId}")// 注意这种写法
//    @RequestMapping(value = "/to_detail/{goodsId}")// 注意这种写法
    @ResponseBody
    public Result<GoodsDetailVo> toDetailStatic(SeckillUser user, @PathVariable("goodsId") long goodsId) {

        // 通过商品id再数据库查询
        GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);

        // 获取商品的秒杀开始与结束的时间
        long startDate = goods.getStartDate().getTime();
        long endDate = goods.getEndDate().getTime();
        long now = System.currentTimeMillis();

        // 秒杀状态; 0: 秒杀未开始，1: 秒杀进行中，2: 秒杀已结束
        int miaoshaStatus = 0;
        // 秒杀剩余时间
        int remainSeconds = 0;

        if (now < startDate) { // 秒杀未开始
            miaoshaStatus = 0;
            remainSeconds = (int) ((startDate - now) / 1000);
        } else if (now > endDate) { // 秒杀已结束
            miaoshaStatus = 2;
            remainSeconds = -1;
        } else { // 秒杀进行中
            miaoshaStatus = 1;
            remainSeconds = 0;
        }

        // 服务端封装商品数据直接传递给客户端，而不用渲染页面
        GoodsDetailVo goodsDetailVo = new GoodsDetailVo();
        goodsDetailVo.setGoods(goods);
        goodsDetailVo.setUser(user);
        goodsDetailVo.setRemainSeconds(remainSeconds);
        goodsDetailVo.setSeckillStatus(miaoshaStatus);

        return Result.success(goodsDetailVo);
    }

}
