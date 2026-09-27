package com.lrs.buddy.modules.sys.model.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户新增/修改表单。
 *
 * <p>为什么不直接用实体 {@code SysUser} 接收参数：
 * <ul>
 *   <li>实体没有角色 ID 字段，用实体接收就只能靠"塞进 remark 再解析"这类歪招</li>
 *   <li>实体字段与数据库一一对应，直接用它接收请求等于把表结构暴露给前端，
 *       以后调整表结构会直接影响接口契约</li>
 * </ul>
 */
@Data
public class SysUserForm {

    /** 修改时必填 */
    private Long id;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 50, message = "用户名长度需在 2~50 之间")
    private String username;

    /** 新增时必填；修改时留空表示不改密码 */
    @Size(max = 100, message = "密码长度不能超过 100")
    private String password;

    @Size(max = 50, message = "昵称长度不能超过 50")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100")
    private String email;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    private String avatar;

    /** 状态：0 正常，1 停用 */
    private Integer status;

    private String remark;

    /** 该用户拥有的角色 */
    private List<Long> roleIds;
}
