package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 调用者身份解析：把“这次请求是谁发的”收敛到一个地方。
 *
 * <p>此前 {@code userId} 直接取自请求体，而 {@code GET/DELETE} 类接口（查任务、订阅事件、取消任务）
 * 根本没有身份可言——任务表里的 {@code user_id} 列因此从不参与查询，等于形同虚设。
 * 现在统一按下面的顺序取身份：</p>
 *
 * <ol>
 *   <li>请求头 {@value #USER_HEADER}：由网关 / 认证层注入，是唯一可信来源；</li>
 *   <li>请求体 {@code userId}：仅作为本地单用户调试的兜底，
 *       可通过 {@code tender.auth.allow-body-user-id=false} 关闭；</li>
 *   <li>都没有：归为匿名 {@value #ANONYMOUS}，与 Agent 状态存储共用同一套命名空间。</li>
 * </ol>
 *
 * <p>生产环境应把 {@code allow-body-user-id} 设为 {@code false}：请求体是客户端可控的，
 * 拿它当身份等同于没有校验。关闭后缺少请求头一律 401。</p>
 */
@Slf4j
@Component
public class CallerIdentity {

    /** 调用者身份请求头；接入真实认证后由网关注入，前端不要再自己伪造 */
    public static final String USER_HEADER = "X-User-Id";

    /** 匿名用户占位；与 {@code t_tender_task.user_id} 默认值、Agent 状态存储保持一致 */
    public static final String ANONYMOUS = "__anon__";

    /** 与 {@code t_tender_task.user_id} 列宽一致 */
    private static final int USER_ID_MAX = 128;

    /**
     * 是否允许用请求体 {@code userId} 兜底。
     *
     * <p>默认 {@code true} 只为了本地/演示环境直连可用；对外部署务必设为 {@code false}，
     * 否则调用者改一个字段就能冒充别人。</p>
     */
    @Value("${tender.auth.allow-body-user-id:true}")
    private boolean allowBodyUserId;

    @PostConstruct
    void warnOnInsecureDefault() {
        if (allowBodyUserId) {
            log.warn("tender.auth.allow-body-user-id=true：当前用请求体 userId 充当调用者身份，"
                    + "请求体可被伪造，仅适用于本地单用户调试；对外部署请设为 false 并由网关注入 {}",
                    USER_HEADER);
        }
    }

    /**
     * 解析调用者身份。
     *
     * @param headerUserId 请求头 {@value #USER_HEADER} 的值，可为空
     * @param bodyUserId   请求体里的 {@code userId}，可为空；仅在不要求请求头时生效
     * @return 归一化后的用户ID，永不为空（最少是 {@value #ANONYMOUS}）
     */
    public String resolve(String headerUserId, String bodyUserId) {
        if (StringUtils.hasText(headerUserId)) {
            return normalize(headerUserId);
        }
        if (StringUtils.hasText(bodyUserId)) {
            if (!allowBodyUserId) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "缺少 " + USER_HEADER + " 请求头");
            }
            return normalize(bodyUserId);
        }
        return ANONYMOUS;
    }

    /**
     * 归一化用户ID：去空白、超长直接拒绝。
     *
     * <p>不做截断：截断会把两个不同的用户映射成同一行，反而制造越权。
     * 空白值归为匿名，与 {@code newTask} 的历史行为一致。</p>
     */
    public static String normalize(String userId) {
        if (!StringUtils.hasText(userId)) {
            return ANONYMOUS;
        }
        String trimmed = userId.trim();
        if (trimmed.length() > USER_ID_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "用户ID 长度不能超过 " + USER_ID_MAX + " 个字符");
        }
        return trimmed;
    }
}
