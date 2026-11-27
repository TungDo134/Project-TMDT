package com.example.ecommerce.service;

import com.example.ecommerce.configuration.VNPayConfig;
import com.example.ecommerce.entity.Order;
import com.example.ecommerce.entity.Payment;
import com.example.ecommerce.enums.OrderStatus;
import com.example.ecommerce.enums.PaymentGateway;
import com.example.ecommerce.enums.PaymentResult;
import com.example.ecommerce.enums.PaymentStatus;
import com.example.ecommerce.exception.AppException;
import com.example.ecommerce.exception.ErrorCode;
import com.example.ecommerce.repository.CartItemRepository;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VNPayService {

    private final VNPayConfig vnPayConfig;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartItemRepository cartItemRepository;

    public String createPaymentUrl(String orderId, String ipAddress) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        long amount = Math.round(order.getTotalAmount() * 100);

        // ĐỒI TẠI ĐÂY: Dùng trực tiếp orderId làm vnp_TxnRef để VNPay lưu vết và gửi trả lại chính xác mã đơn hàng này
        String vnp_TxnRef = orderId;

        saveOrUpdatePayment(order, vnp_TxnRef, orderId);

        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", vnPayConfig.getVersion());
        vnp_Params.put("vnp_Command", vnPayConfig.getCommand());
        vnp_Params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        vnp_Params.put("vnp_Amount", String.valueOf(amount));
        vnp_Params.put("vnp_CurrCode", vnPayConfig.getCurrencyCode());
        vnp_Params.put("vnp_TxnRef", vnp_TxnRef);
        vnp_Params.put("vnp_OrderInfo", "Thanh toan don hang:" + vnp_TxnRef);
        vnp_Params.put("vnp_OrderType", vnPayConfig.getOrderType());
        vnp_Params.put("vnp_Locale", vnPayConfig.getLocale());
        vnp_Params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
        vnp_Params.put("vnp_IpAddr", ipAddress);

        // Môi trường Test mặc định chọn ngân hàng mô phỏng NCB
        vnp_Params.put("vnp_BankCode", "NCB");

        Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+7"));
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        String vnp_CreateDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);

        cld.add(Calendar.MINUTE, 15);
        String vnp_ExpireDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);

        String queryUrl = vnp_Params.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((first, second) -> first + "&" + second)
                .orElseThrow();
        String vnp_SecureHash = sign(vnp_Params);
        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;

        return vnPayConfig.getPaymentUrl() + "?" + queryUrl;
    }

    @Transactional
    public boolean processCallback(Map<String, String> fields) {
        String secureHash = fields.get("vnp_SecureHash");
        if (secureHash == null || !secureHash.matches("(?i)[0-9a-f]{128}")
                || !MessageDigest.isEqual(
                        sign(fields).getBytes(StandardCharsets.US_ASCII),
                        secureHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Invalid VNPay signature");
        }
        if (vnPayConfig.getTmnCode() == null || vnPayConfig.getTmnCode().isBlank()) {
            throw new IllegalStateException("VNPay terminal code is not configured");
        }

        String txnRef = fields.get("vnp_TxnRef");
        if (txnRef == null || txnRef.isBlank()) {
            throw new IllegalArgumentException("Missing VNPay transaction reference");
        }

        Payment payment = paymentRepository.findByTransactionId(txnRef)
                .orElseThrow(() -> new IllegalArgumentException("Unknown VNPay transaction reference"));
        if (payment.getGateway() != PaymentGateway.VNPAY) {
            throw new IllegalArgumentException("Unexpected payment gateway");
        }

        long callbackAmount;
        try {
            callbackAmount = Long.parseLong(fields.get("vnp_Amount"));
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid VNPay amount", e);
        }
        long expectedAmount = Math.round(payment.getAmount() * 100);
        if (callbackAmount != expectedAmount
                || !Objects.equals(vnPayConfig.getTmnCode(), fields.get("vnp_TmnCode"))) {
            throw new IllegalArgumentException("VNPay payment details do not match");
        }

        Order order = orderRepository.findByIdForUpdate(payment.getOrder().getId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (payment.getResult() == PaymentResult.SUCCESS
                && order.getPaymentStatus() == PaymentStatus.PAID) {
            return true;
        }
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.DELIVERED) {
            return false;
        }

        payment.setGatewayTransactionNo(fields.get("vnp_TransactionNo"));
        boolean paymentSucceeded = "00".equals(fields.get("vnp_ResponseCode"))
                && "00".equals(fields.get("vnp_TransactionStatus"));

        if (paymentSucceeded) {
            if (payment.getGatewayTransactionNo() == null
                    || payment.getGatewayTransactionNo().isBlank()) {
                throw new IllegalArgumentException("Missing VNPay gateway transaction number");
            }
            order.setPaymentStatus(PaymentStatus.PAID);
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.CONFIRMED);
            }
            payment.setResult(PaymentResult.SUCCESS);
            payment.setPaidAt(Instant.now());
            cartItemRepository.deleteByUserId(order.getUser().getId());
        } else {
            order.setPaymentStatus(PaymentStatus.FAILED);
            payment.setResult("24".equals(fields.get("vnp_ResponseCode"))
                    ? PaymentResult.CANCELLED
                    : PaymentResult.FAILED);
            releaseAuctionCheckout(order);
        }

        orderRepository.save(order);
        paymentRepository.save(payment);
        return paymentSucceeded;
    }

    private void saveOrUpdatePayment(Order order, String txnRef, String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElse(Payment.builder()
                        .order(order)
                        .amount(order.getTotalAmount())
                        .gateway(PaymentGateway.VNPAY)
                        .build());
        payment.setTransactionId(txnRef);
        payment.setAmount(order.getTotalAmount());
        payment.setResult(PaymentResult.PENDING);
        paymentRepository.save(payment);
    }

    private void releaseAuctionCheckout(Order order) {
        for (var item : order.getItems()) {
            if (Boolean.TRUE.equals(item.getProduct().getIsAuction())) {
                item.getProduct().setAuctionPaid(false);
            }
        }
    }

    private String sign(Map<String, String> fields) {
        if (vnPayConfig.getHashSecret() == null || vnPayConfig.getHashSecret().isBlank()) {
            throw new IllegalStateException("VNPay hash secret is not configured");
        }
        String hashData = fields.entrySet().stream()
                .filter(entry -> !"vnp_SecureHash".equals(entry.getKey())
                        && !"vnp_SecureHashType".equals(entry.getKey())
                        && entry.getValue() != null
                        && !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
                .reduce((first, second) -> first + "&" + second)
                .orElse("");
        return hmacSHA512(vnPayConfig.getHashSecret(), hashData);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String hmacSHA512(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Loi tao chu ky HMAC-SHA512", e);
        }
    }
}