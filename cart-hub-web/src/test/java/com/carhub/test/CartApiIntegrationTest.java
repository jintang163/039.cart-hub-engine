package com.carhub.test;

import com.carhub.common.context.CartContext;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.result.R;
import com.carhub.common.result.ResultCode;
import com.carhub.domain.dto.AddCartItemDTO;
import com.carhub.domain.dto.BatchCartItemDTO;
import com.carhub.domain.dto.MergeCartDTO;
import com.carhub.domain.dto.UpdateCartItemDTO;
import com.carhub.domain.model.CartItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("购物车API集成测试")
public class CartApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "test-tenant";
    private static final String BIZ_TYPE = "ecommerce";
    private static final String USER_ID = "test-user-001";

    @BeforeEach
    public void setUp() {
        CartContext ctx = new CartContext();
        ctx.setTenantId(TENANT_ID);
        ctx.setBizType(BIZ_TYPE);
        ctx.setUserId(USER_ID);
        ctx.setSource("test");
        CartContextHolder.setContext(ctx);
    }

    @AfterEach
    public void tearDown() {
        CartContextHolder.clear();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Tenant-Id", TENANT_ID);
        h.put("X-Biz-Type", BIZ_TYPE);
        h.put("X-User-Id", USER_ID);
        h.put("X-Source", "test");
        return h;
    }

    private <T> T parseResult(String json, Class<T> dataClass) throws Exception {
        R<?> r = objectMapper.readValue(json, R.class);
        assertNotNull(r);
        assertEquals(ResultCode.SUCCESS.getCode(), r.getCode(), "响应码应为200: " + r.getMessage());
        if (r.getData() == null) return null;
        return objectMapper.convertValue(r.getData(), dataClass);
    }

    @Test
    @Order(1)
    @DisplayName("清空购物车(测试准备)")
    public void testClearCart() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/cart/clear")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        log.info("清空购物车响应: {}", result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @Order(2)
    @DisplayName("添加商品 - SKU001")
    public void testAddItem1() throws Exception {
        AddCartItemDTO dto = new AddCartItemDTO();
        dto.setSkuId("SKU001");
        dto.setItemName("测试商品001");
        dto.setUnitPrice(new BigDecimal("99.99"));
        dto.setQuantity(2);
        dto.setItemImage("http://example.com/img1.jpg");
        dto.setSelected(true);

        MvcResult result = mockMvc.perform(post("/api/cart/item")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("添加SKU001响应: {}", body);
        Boolean data = parseResult(body, Boolean.class);
        assertTrue(Boolean.TRUE.equals(data), "添加应成功");
    }

    @Test
    @Order(3)
    @DisplayName("添加商品 - SKU002")
    public void testAddItem2() throws Exception {
        AddCartItemDTO dto = new AddCartItemDTO();
        dto.setSkuId("SKU002");
        dto.setItemName("测试商品002");
        dto.setUnitPrice(new BigDecimal("199.50"));
        dto.setQuantity(3);

        MvcResult result = mockMvc.perform(post("/api/cart/item")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Boolean data = parseResult(body, Boolean.class);
        assertTrue(Boolean.TRUE.equals(data));
    }

    @Test
    @Order(4)
    @DisplayName("获取购物车 - 不校验")
    public void testGetCartSimple() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cart/simple")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("购物车内容: {}", body);

        Map data = parseResult(body, Map.class);
        assertNotNull(data);
        assertEquals(2, data.get("itemCount"), "应有2项商品");
        assertEquals(5, data.get("totalQuantity"), "总数量=2+3=5");
    }

    @Test
    @Order(5)
    @DisplayName("修改SKU001数量")
    public void testUpdateItem() throws Exception {
        UpdateCartItemDTO dto = new UpdateCartItemDTO();
        dto.setSkuId("SKU001");
        dto.setQuantity(5);

        MvcResult result = mockMvc.perform(put("/api/cart/item")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Boolean data = parseResult(body, Boolean.class);
        assertTrue(Boolean.TRUE.equals(data));
    }

    @Test
    @Order(6)
    @DisplayName("增量增减数量")
    public void testIncrementQuantity() throws Exception {
        MvcResult result = mockMvc.perform(patch("/api/cart/item/quantity")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .param("skuId", "SKU001")
                        .param("delta", "-2"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("增减数量结果: {}", body);
    }

    @Test
    @Order(7)
    @DisplayName("获取购物车汇总")
    public void testGetSummary() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cart/summary")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map data = parseResult(body, Map.class);
        assertNotNull(data);
        assertTrue(data.containsKey("totalAmount"));
    }

    @Test
    @Order(8)
    @DisplayName("合并本地购物车")
    public void testMergeCart() throws Exception {
        MergeCartDTO dto = new MergeCartDTO();
        CartItem localItem = CartItem.builder()
                .skuId("SKU003")
                .itemName("本地商品")
                .unitPrice(new BigDecimal("50"))
                .quantity(1)
                .build();
        localItem.recalculate();
        dto.setItems(Arrays.asList(localItem));
        dto.setSourceUserId("device-anon-001");

        MvcResult result = mockMvc.perform(post("/api/cart/merge")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("合并结果: {}", body);
    }

    @Test
    @Order(9)
    @DisplayName("批量删除SKU")
    public void testBatchRemove() throws Exception {
        BatchCartItemDTO dto = new BatchCartItemDTO();
        dto.setSkuIds(Arrays.asList("SKU003"));

        MvcResult result = mockMvc.perform(delete("/api/cart/items")
                        .headers(org.springframework.http.HttpHeaders.writableHttpHeaders(
                                buildHeaders(headers())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Long count = parseResult(body, Long.class);
        assertNotNull(count);
        assertTrue(count >= 0);
    }

    private org.springframework.http.HttpHeaders buildHeaders(Map<String, String> map) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        map.forEach(h::set);
        return h;
    }
}
