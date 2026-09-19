package com.zjl.worklog.ticket;

import com.zjl.worklog.security.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报修人点叉删图必须走公开接口：他们没有登录态，不能调 /api/oss/object。
 */
class PublicTicketDeleteGuardTest {

    @Test
    @DisplayName("公开删图接口存在，且不要求登录")
    void publicDeleteObjectExistsAndIsAnonymous() throws Exception {
        var m = PublicTicketController.class.getMethod(
                "deleteObject", PublicTicketController.DeleteObjectRequest.class);
        PostMapping mapping = m.getAnnotation(PostMapping.class);
        assertNotNull(mapping, "报修人删图必须是公开 POST");
        assertTrue(java.util.Arrays.asList(mapping.value()).contains("/delete-object")
                        || java.util.Arrays.asList(mapping.path()).contains("/delete-object"),
                "路径应是 /delete-object，和 upload-policy 同一套凭证模型");
        assertNull(m.getAnnotation(RequireRole.class), "公开接口不能挂登录门槛");
        assertNull(PublicTicketController.class.getAnnotation(RequireRole.class),
                "公开控制器类级也不能要求登录");
    }
}
