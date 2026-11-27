package com.example.ecommerce.service;

import com.example.ecommerce.configuration.VNPayConfig;
import com.example.ecommerce.entity.Order;
import com.example.ecommerce.entity.Payment;
import com.example.ecommerce.entity.User;
import com.example.ecommerce.enums.OrderStatus;
import com.example.ecommerce.enums.PaymentGateway;
import com.example.ecommerce.enums.PaymentResult;
import com.example.ecommerce.enums.PaymentStatus;
import com.example.ecommerce.repository.CartItemRepository;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VNPayServiceTest {

    private static final String HASH_SECRET = "test-hash-secret";
    private static final String ORDER_ID = "order-1";

    @Mock
    private VNPayConfig vnPayConfig;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CartItemRepository cartItemRepository;

    private VNPayService vnPayService;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        vnPayService = new VNPayService(
                vnPayConfig, orderRepository, paymentRepository, cartItemRepository);
        when(vnPayConfig.getHashSecret()).thenReturn(HASH_SECRET);
        lenient().when(vnPayConfig.getTmnCode()).thenReturn("TESTCODE");

        User user = new User();
        user.setId("user-1");
        order = Order.builder()
                .id(ORDER_ID)
                .user(user)
                .status(OrderStatus.PENDING)
                .paymentStatus(PaymentStatus.UNPAID)
                .build();
        payment = Payment.builder()
                .order(order)
                .amount(1_050_000.0)
                .gateway(PaymentGateway.VNPAY)
                .transactionId(ORDER_ID)
                .result(PaymentResult.PENDING)
                .build();
        lenient().when(paymentRepository.findByTransactionId(ORDER_ID)).thenReturn(Optional.of(payment));
        lenient().when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    }

    @Test
    void successfulCallbackVerifiesSignatureAndMarksOrderPaid() throws Exception {
        Map<String, String> callback = signedCallback("00", "00");

        assertTrue(vnPayService.processCallback(callback));

        assertTrue(order.getStatus() == OrderStatus.CONFIRMED);
        assertTrue(order.getPaymentStatus() == PaymentStatus.PAID);
        assertTrue(payment.getResult() == PaymentResult.SUCCESS);
        verify(cartItemRepository).deleteByUserId("user-1");
        verify(orderRepository).save(order);
        verify(paymentRepository).save(payment);
    }

    @Test
    void failedCallbackDoesNotConfirmOrderOrMarkItPaid() throws Exception {
        Map<String, String> callback = signedCallback("24", "中断");

        assertFalse(vnPayService.processCallback(callback));

        assertTrue(order.getStatus() == OrderStatus.PENDING);
        assertTrue(order.getPaymentStatus() == PaymentStatus.FAILED);
        assertTrue(payment.getResult() == PaymentResult.CANCELLED);
        verify(cartItemRepository, never()).deleteByUserId("user-1");
    }

    @Test
    void callbackWithChangedDataAndOriginalSignatureIsRejected() throws Exception {
        Map<String, String> callback = signedCallback("00", "00");
        callback.put("vnp_Amount", "1");

        assertThrows(IllegalArgumentException.class, () -> vnPayService.processCallback(callback));

        verify(paymentRepository, never()).findByTransactionId(ORDER_ID);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void callbackWithMismatchedAmountIsRejectedEvenWithValidSignature() throws Exception {
        Map<String, String> callback = signedCallback("00", "00");
        callback.put("vnp_Amount", "100");
        callback.put("vnp_SecureHash", signature(callback));

        assertThrows(IllegalArgumentException.class, () -> vnPayService.processCallback(callback));

        verify(orderRepository, never()).save(order);
    }

    private Map<String, String> signedCallback(String responseCode, String transactionStatus)
            throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vnp_TmnCode", "TESTCODE");
        fields.put("vnp_TxnRef", ORDER_ID);
        fields.put("vnp_Amount", "105000000");
        fields.put("vnp_ResponseCode", responseCode);
        fields.put("vnp_TransactionStatus", transactionStatus);
        fields.put("vnp_TransactionNo", "gateway-transaction-1");
        fields.put("vnp_SecureHash", signature(fields));
        return fields;
    }

    private String signature(Map<String, String> fields) throws Exception {
        String hashData = fields.entrySet().stream()
                .filter(entry -> !"vnp_SecureHash".equals(entry.getKey())
                        && !"vnp_SecureHashType".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((first, second) -> first + "&" + second)
                .orElse("");

        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(HASH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        byte[] bytes = mac.doFinal(hashData.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
