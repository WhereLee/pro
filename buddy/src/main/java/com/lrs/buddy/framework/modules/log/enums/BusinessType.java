package com.lrs.buddy.framework.modules.log.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 操作类型。
 */
@Getter
@AllArgsConstructor
public enum BusinessType {

    OTHER(0, "其它"),
    INSERT(1, "新增"),
    UPDATE(2, "修改"),
    DELETE(3, "删除"),
    EXPORT(4, "导出"),
    IMPORT(5, "导入"),
    GRANT(6, "授权"),
    LOGIN(7, "登录"),
    LOGOUT(8, "登出"),
    KICK(9, "强制下线");

    private final int code;
    private final String desc;

    public static String descOf(Integer code) {
        if (code == null) {
            return OTHER.getDesc();
        }
        for (BusinessType type : values()) {
            if (type.code == code) {
                return type.getDesc();
            }
        }
        return OTHER.getDesc();
    }
}
