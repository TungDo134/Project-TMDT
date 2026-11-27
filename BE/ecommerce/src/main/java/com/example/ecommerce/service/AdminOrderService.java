package com.example.ecommerce.service;

import com.example.ecommerce.dto.response.OrderResponse;
import com.example.ecommerce.entity.Order;
import com.example.ecommerce.enums.OrderStatus;
import com.example.ecommerce.enums.PaymentMethod;
import com.example.ecommerce.enums.PaymentStatus;
import com.example.ecommerce.exception.AppException;
import com.example.ecommerce.exception.ErrorCode;
import com.example.ecommerce.repository.OrderRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders(String status) {
        List<Order> orders;
        if (status != null && !status.isBlank()) {
            try {
                OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
                orders = orderRepository.findAllByStatusOrderByCreatedAtDesc(orderStatus);
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.VALIDATION_ERROR);
            }
        } else {
            orders = orderRepository.findAllByOrderByCreatedAtDesc();
        }
        return orders.stream().map(OrderResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetail(String orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse updateOrderStatus(String orderId, String status) {
        final OrderStatus newStatus;
        try {
            newStatus = OrderStatus.valueOf(status == null ? "" : status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }

        if (newStatus == OrderStatus.CANCELLED) {
            return orderService.cancelOrderByAdmin(orderId);
        }

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        validateStatusTransition(order.getStatus(), newStatus);

        order.setStatus(newStatus);

        // COD + DELIVERED → tự động PAID
        if (newStatus == OrderStatus.DELIVERED
                && order.getPaymentMethod() == PaymentMethod.COD) {
            order.setPaymentStatus(PaymentStatus.PAID);
        }

        return OrderResponse.from(orderRepository.save(order));
    }

    private void validateStatusTransition(OrderStatus current, OrderStatus next) {
        if (current == OrderStatus.DELIVERED || current == OrderStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        Map<OrderStatus, Integer> stepMap = Map.of(
                OrderStatus.PENDING,   0,
                OrderStatus.CONFIRMED, 1,
                OrderStatus.SHIPPING,  2,
                OrderStatus.DELIVERED, 3,
                OrderStatus.CANCELLED, -1
        );
        Integer currentStep = stepMap.get(current);
        Integer nextStep    = stepMap.get(next);
        if (currentStep == null || nextStep == null || nextStep != currentStep + 1) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    @Transactional(readOnly = true)
    public OrderStats getStats() {
        List<Order> all = orderRepository.findAll();
        return OrderStats.builder()
                .total(all.size())
                .pending(all.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count())
                .confirmed(all.stream().filter(o -> o.getStatus() == OrderStatus.CONFIRMED).count())
                .shipping(all.stream().filter(o -> o.getStatus() == OrderStatus.SHIPPING).count())
                .delivered(all.stream().filter(o -> o.getStatus() == OrderStatus.DELIVERED).count())
                .cancelled(all.stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED).count())
                .totalRevenue(all.stream()
                        .filter(o -> o.getStatus() == OrderStatus.DELIVERED)
                        .mapToDouble(Order::getTotalAmount).sum())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderStats {
        private long total;
        private long pending;
        private long confirmed;
        private long shipping;
        private long delivered;
        private long cancelled;
        private double totalRevenue;
    }
}