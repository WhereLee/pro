package com.lrs.buddy.framework.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据范围。
 *
 * <p>数值越小权限越大：一个用户有多个角色时取<b>最小值</b>，
 * 也就是按最宽松的那个角色来。这与"权限只增不减"的直觉一致——
 * 用户被授予某个角色，就应该获得该角色承诺的全部数据可见性。
 */
@Getter
@AllArgsConstructor
public enum DataScopeType {

    ALL(1, "全部数据"),
    CUSTOM(2, "自定义部门"),
    DEPT(3, "仅本部门"),
    DEPT_AND_CHILD(4, "本部门及以下"),
    SELF(5, "仅本人");

    private final int code;
    private final String desc;

    public static DataScopeType of(Integer code) {
        if (code == null) {
            return SELF;
        }
        for (DataScopeType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return SELF;
    }
}
