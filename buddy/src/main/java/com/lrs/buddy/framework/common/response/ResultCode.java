package com.lrs.buddy.framework.common.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一业务码表。
 *
 * <p>HTTP 状态码只表达"协议层"语义，无法区分业务场景，因此业务码独立编号：
 * <ul>
 *   <li>2xx 沿用成功语义</li>
 *   <li>1xxx 前缀表示鉴权相关（与 HTTP 401/403 区分开，便于前端分别处理
 *       "跳登录页"和"提示无权限"）</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证，请先登录"),
    FORBIDDEN(403, "无权限访问此资源"),
    NOT_FOUND(404, "资源不存在"),
    /** 并发写冲突：@Version 乐观锁 CAS 失败，数据已被其他操作修改，前端应提示稍后重试 */
    CONFLICT(409, "操作冲突，数据已被其他操作修改，请重试"),

    /** 令牌过期：前端应引导用户重新登录 */
    TOKEN_EXPIRED(1401, "登录已过期，请重新登录"),
    /** 令牌解析失败/被篡改 */
    TOKEN_INVALID(1402, "令牌无效"),
    /** 被管理员强制下线：前端应弹窗提示后跳登录页 */
    FORCED_OFFLINE(1403, "账号已在其他地点登录或被管理员强制下线"),
    /** 重复提交拦截 */
    REPEAT_SUBMIT(1404, "操作过于频繁，请勿重复提交"),

    ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;
}
