package com.deepresearch;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 离线 Spring 上下文装配测试。
 *
 * 数据库、pgvector 和外部模型属于集成边界；这里用测试替身验证
 * Controller、Service、Security 与配置类能够在没有本地容器时完成装配。
 */
@SpringBootTest
@ActiveProfiles("unit-test")
class DeepResearchApplicationTests {

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore vectorStore;

    @Test
    void contextLoads() {
        // 能跑到这里说明不依赖手工启动的数据库也能完成核心 Bean 装配。
    }
}
