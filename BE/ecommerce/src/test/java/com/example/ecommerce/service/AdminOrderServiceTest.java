package com.example.ecommerce.service;

import com.example.ecommerce.entity.Order;
import com.example.ecommerce.enums.OrderStatus;
import com.example.ecommerce.exception.AppException;
import com.example.ecommerce.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    private AdminOrderService adminOrderService;

    @BeforeEach
    void setUp() {
        adminOrderService = new AdminOrderService(orderRepository, orderService);
    }

    @Test
    void rejectsInvalidStatusString() {
        assertThrows(AppException.class,
                () -> adminOrderService.updateOrderStatus("order-1", "INVALID_STATUS"));

        verify(orderRepository, never()).findByIdForUpdate("order-1");
    }

    @Test
    void rejectsStatusTransitionThatSkipsSteps() {
        Order order = new Order();
        order.setId("order-1");
        order.setStatus(OrderStatus.PENDING);

        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));

        assertThrows(AppException.class,
                () -> adminOrderService.updateOrderStatus("order-1", "DELIVERED"));

        verify(orderRepository, never()).save(order);
    }

    @Test
    void rejectsBackwardStatusTransition() {
        Order order = new Order();
        order.setId("order-1");
        order.setStatus(OrderStatus.SHIPPING);

        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));

        assertThrows(AppException.class,
                () -> adminOrderService.updateOrderStatus("order-1", "CONFIRMED"));

        verify(orderRepository, never()).save(order);
    }
}
