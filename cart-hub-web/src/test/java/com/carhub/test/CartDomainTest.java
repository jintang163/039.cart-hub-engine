package com.carhub.test;

import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("购物车领域模型单元测试")
public class CartDomainTest {

    private CartItem testItem1;
    private CartItem testItem2;
    private Cart cart;

    @BeforeEach
    public void setUp() {
        testItem1 = CartItem.builder()
                .skuId("SKU001")
                .spuId("SPU001")
                .itemName("测试商品1")
                .unitPrice(new BigDecimal("99.99"))
                .originalPrice(new BigDecimal("199.99"))
                .quantity(2)
                .selected(true)
                .build();
        testItem1.recalculate();

        testItem2 = CartItem.builder()
                .skuId("SKU002")
                .itemName("测试商品2")
                .unitPrice(new BigDecimal("199.50"))
                .quantity(3)
                .selected(true)
                .build();
        testItem2.recalculate();

        List<CartItem> items = new ArrayList<>(Arrays.asList(testItem1, testItem2));
        cart = Cart.builder()
                .tenantId("default")
                .bizType("ecommerce")
                .userId("user001")
                .items(items)
                .discounts(new ArrayList<>())
                .build();
        cart.recalculate();
    }

    @Test
    @DisplayName("CartItem小计金额计算正确")
    public void testCartItemSubtotal() {
        assertEquals(new BigDecimal("199.98"), testItem1.getSubtotal(),
                "单价99.99 * 数量2 = 199.98");
        assertEquals(new BigDecimal("598.50"), testItem2.getSubtotal(),
                "单价199.50 * 数量3 = 598.50");
    }

    @Test
    @DisplayName("购物车总金额计算正确")
    public void testCartTotal() {
        assertEquals(2, cart.getItemCount(), "商品项数为2");
        assertEquals(5, cart.getTotalQuantity(), "总数量 2+3=5");
        assertEquals(new BigDecimal("798.48"), cart.getTotalAmount(),
                "总金额 199.98+598.50 = 798.48");
    }

    @Test
    @DisplayName("未选中商品不计入总金额")
    public void testCartSelectedOnly() {
        testItem2.setSelected(false);
        cart.recalculate();
        assertEquals(2, cart.getItemCount(), "商品项数仍为2");
        assertEquals(2, cart.getTotalQuantity(), "选中数量为2");
        assertEquals(new BigDecimal("199.98"), cart.getTotalAmount(),
                "只计算选中的商品1的金额");
    }

    @Test
    @DisplayName("添加商品到购物车")
    public void testAddItem() {
        CartItem newItem = CartItem.builder()
                .skuId("SKU003").itemName("新商品")
                .unitPrice(new BigDecimal("50")).quantity(1).build();
        assertTrue(cart.addItem(newItem), "添加新商品应成功");
        assertEquals(3, cart.getItemCount(), "应变为3项");
    }

    @Test
    @DisplayName("重复SKU不能添加")
    public void testAddDuplicateSku() {
        CartItem dup = CartItem.builder().skuId("SKU001").quantity(5).build();
        assertFalse(cart.addItem(dup), "重复SKU应添加失败");
        assertEquals(2, cart.getItemCount());
    }

    @Test
    @DisplayName("按SKU查找商品")
    public void testGetItemBySku() {
        CartItem found = cart.getItemBySku("SKU001");
        assertNotNull(found);
        assertEquals("测试商品1", found.getItemName());
        assertNull(cart.getItemBySku("NOT_EXIST"));
    }

    @Test
    @DisplayName("移除商品")
    public void testRemoveItem() {
        assertTrue(cart.removeItem("SKU001"));
        assertEquals(1, cart.getItemCount());
        assertFalse(cart.removeItem("NOT_EXIST"));
    }

    @Test
    @DisplayName("Redis Key构建正确")
    public void testRedisKey() {
        String key = RedisKeyConstant.buildCartKey("t1", "biz1", "u1");
        assertTrue(key.contains("t1") && key.contains("biz1") && key.contains("u1"));
    }

    @Test
    @DisplayName("价格变动标记")
    public void testPriceChangedFlag() {
        assertFalse(cart.getHasPriceChanged());
        testItem1.setPriceChanged(true);
        testItem1.setOldPrice(new BigDecimal("199.99"));
        cart.recalculate();
        assertTrue(cart.getHasPriceChanged(), "有一项价格变动，标记应为true");
    }

    @Test
    @DisplayName("商品下架判定为无效")
    public void testInvalidItem() {
        assertFalse(cart.getHasInvalidItem());
        testItem1.setOnShelf(false);
        cart.recalculate();
        assertTrue(cart.getHasInvalidItem());
    }

    @Test
    @DisplayName("购物车排序 - 按加入时间倒序")
    public void testItemsSorted() {
        testItem1.setAddTime(1000L);
        testItem2.setAddTime(2000L);
        List<CartItem> sorted = cart.getItemsSortedByAddTime();
        assertEquals("SKU002", sorted.get(0).getSkuId(), "后加入的(时间大)排在前");
    }
}
