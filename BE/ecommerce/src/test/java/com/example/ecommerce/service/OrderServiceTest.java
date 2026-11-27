package com.example.ecommerce.service;

import com.example.ecommerce.dto.request.auth.CheckoutRequest;
import com.example.ecommerce.entity.Order;
import com.example.ecommerce.entity.OrderItem;
import com.example.ecommerce.entity.Product;
import com.example.ecommerce.entity.User;
import com.example.ecommerce.enums.OrderStatus;
import com.example.ecommerce.enums.PaymentMethod;
import com.example.ecommerce.enums.PaymentStatus;
import com.example.ecommerce.exception.AppException;
import com.example.ecommerce.repository.AuctionBidRepository;
import com.example.ecommerce.repository.CartItemRepository;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.PaymentRepository;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuctionBidRepository auctionBidRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private PaymentRepository paymentRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository,
                productRepository,
                userRepository,
                auctionBidRepository,
                cartItemRepository,
                paymentRepository);
    }

    @Test
    void checkoutUsesFreeShippingAtThreshold() {
        User user = new User();
        user.setId("user-1");
        Product product = new Product();
        product.setId(1L);
        product.setName("Test product");
        product.setPrice(2_500_000.0);
        product.setStockQuantity(2);
        product.setIsAuction(false);
        product.setIsApproved(true);
        product.setIsDeleted(false);

        CheckoutRequest request = new CheckoutRequest();
        request.setReceiverName("Test");
        request.setReceiverPhone("0901234567");
        request.setShippingAddress("Test address");
        request.setCity("Hà Nội");
        request.setPaymentMethod(PaymentMethod.COD);
        CheckoutRequest.OrderItemRequest item = new CheckoutRequest.OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(2);
        request.setItems(new ArrayList<>(java.util.List.of(item)));

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = orderService.checkout("user-1", request);

        assertEquals(5_000_000.0, result.getTotalAmount());
        assertEquals(0, product.getStockQuantity());
        verify(cartItemRepository).deleteByUserId("user-1");
    }

    @Test
    void checkoutAddsShippingFeeBelowFreeShippingThreshold() {
        User user = new User();
        user.setId("user-1");
        Product product = new Product();
        product.setId(1L);
        product.setName("Test product");
        product.setPrice(1_000_000.0);
        product.setStockQuantity(1);
        product.setIsAuction(false);
        product.setIsApproved(true);
        product.setIsDeleted(false);

        CheckoutRequest request = new CheckoutRequest();
        request.setReceiverName("Test");
        request.setReceiverPhone("0901234567");
        request.setShippingAddress("Test address");
        request.setCity("Hà Nội");
        request.setPaymentMethod(PaymentMethod.COD);
        CheckoutRequest.OrderItemRequest item = new CheckoutRequest.OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);
        request.setItems(new ArrayList<>(java.util.List.of(item)));

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = orderService.checkout("user-1", request);

        assertEquals(1_050_000.0, result.getTotalAmount());
    }

    @Test
    void cancellationRestocksOnceAndRejectsDuplicateCancellation() {
        User user = new User();
        user.setId("user-1");
        Product product = new Product();
        product.setStockQuantity(3);
        product.setIsAuction(false);
        OrderItem item = OrderItem.builder().product(product).quantity(2).unitPrice(100.0).build();
        Order order = Order.builder()
                .id("order-1")
                .user(user)
                .status(OrderStatus.PENDING)
                .paymentMethod(PaymentMethod.COD)
                .paymentStatus(PaymentStatus.UNPAID)
                .items(new ArrayList<>(java.util.List.of(item)))
                .build();
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.empty());
        when(orderRepository.save(order)).thenReturn(order);

        orderService.cancelOrder("user-1", "order-1");

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(5, product.getStockQuantity());
        assertThrows(AppException.class, () -> orderService.cancelOrder("user-1", "order-1"));
        verify(productRepository).save(product);
    }

    @Test
    void cancellationDoesNotRestockPaidOrder() {
        Order order = Order.builder()
                .id("order-1")
                .user(user("user-1"))
                .status(OrderStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PAID)
                .items(new ArrayList<>())
                .build();
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));

        assertThrows(AppException.class, () -> orderService.cancelOrder("user-1", "order-1"));

        verify(productRepository, never()).save(org.mockito.ArgumentMatchers.any(Product.class));
        verify(orderRepository, never()).save(order);
    }

    private User user(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
