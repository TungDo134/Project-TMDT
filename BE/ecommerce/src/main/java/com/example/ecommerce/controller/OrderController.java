package com.example.ecommerce.controller;

import com.example.ecommerce.dto.request.auth.CheckoutRequest;
import com.example.ecommerce.dto.response.ApiResponse;
import com.example.ecommerce.dto.response.OrderResponse;
import com.example.ecommerce.enums.PaymentMethod;
import com.example.ecommerce.service.OrderService;
import com.example.ecommerce.service.VNPayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class OrderController {

    private final OrderService orderService;
    private final VNPayService vnPayService;

    /**
     * 1. API ĐẶT HÀNG (Checkout)
     */
    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<OrderResponse>> checkout(
            Principal principal,
            @Valid @RequestBody CheckoutRequest request,
            HttpServletRequest httpRequest) {

        String userId = principal.getName();
        OrderResponse order = orderService.checkout(userId, request);

        if (request.getPaymentMethod() == PaymentMethod.ONLINE) {
            String ipAddress = httpRequest.getRemoteAddr();
            String paymentUrl = vnPayService.createPaymentUrl(order.getId(), ipAddress);
            order.setPaymentUrl(paymentUrl);
        }

        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .success(true)
                .code("ORDER_CREATED")
                .message("Đặt hàng thành công")
                .data(order)
                .build());
    }

    /**
     * 2. API CALLBACK VNPAY
     * Trả về JSON để Frontend (Next.js) nhận biết trạng thái và tự xử lý giao diện
     */
    @GetMapping("/vnpay-callback")
    public ResponseEntity<ApiResponse<String>> vnpayCallback(HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));

            boolean paymentSucceeded = vnPayService.processCallback(params);

            return ResponseEntity.ok(ApiResponse.<String>builder()
                    .success(true)
                    .code("PAYMENT_VERIFIED")
                    .message(paymentSucceeded
                            ? "Thanh toán thành công"
                            : "Giao dịch thanh toán không thành công")
                    .data(paymentSucceeded ? "SUCCESS" : "FAILED")
                    .build());

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.<String>builder()
                            .success(false)
                            .code("INVALID_PAYMENT_CALLBACK")
                            .message("Callback thanh toán không hợp lệ")
                            .build());
        }
    }

    /**
     * 3. API LẤY CHI TIẾT ĐƠN HÀNG THEO ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderDetail(
            @PathVariable String id,
            Principal principal) {

        OrderResponse order = orderService.getOrderDetail(principal.getName(), id);

        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .success(true)
                .code("ORDER_FETCHED")
                .message("Lấy chi tiết đơn hàng thành công")
                .data(order)
                .build());
    }

    /**
     * 4. API LẤY LỊCH SỬ DANH SÁCH ĐƠN HÀNG CỦA USER
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrderHistory(Principal principal) {
        List<OrderResponse> history = orderService.getOrderHistory(principal.getName());
        return ResponseEntity.ok(ApiResponse.<List<OrderResponse>>builder()
                .success(true)
                .code("HISTORY_FETCHED")
                .message("Lấy lịch sử đơn hàng thành công")
                .data(history)
                .build());
    }
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable String id,
            Principal principal) {
        OrderResponse order = orderService.cancelOrder(principal.getName(), id);
        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .success(true)
                .code("ORDER_CANCELLED")
                .message("Hủy đơn hàng thành công")
                .data(order)
                .build());
    }
}
