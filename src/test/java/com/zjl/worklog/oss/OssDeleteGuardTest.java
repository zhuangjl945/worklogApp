package com.zjl.worklog.oss;

import com.zjl.worklog.security.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class OssDeleteGuardTest {

    @Test
    @DisplayName("删未引用对象不按系统管理员卡：刚上传还没保存的工作记录图片必须能自己撤掉")
    void deleteObjectIsNotAdminOnly() throws Exception {
        var m = OssController.class.getMethod("deleteObject", String.class);
        assertNull(m.getAnnotation(RequireRole.class),
                "孤儿文件删除应只靠登录 + 目录前缀 + 引用检查，不能再要求 ADMIN");
    }
}
