package com.example.ecommerce.controller;

import com.example.ecommerce.dto.response.RevenueChartDTO;
import com.example.ecommerce.dto.response.TopCategoryDTO;
import com.example.ecommerce.entity.Order;
import com.example.ecommerce.entity.OrderItem;
import com.example.ecommerce.entity.Product;
import com.example.ecommerce.enums.OrderStatus;
import com.example.ecommerce.exception.AppException;
import com.example.ecommerce.exception.ErrorCode;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seller/analytics")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class AnalyticsController {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @GetMapping("/revenue")
    public List<RevenueChartDTO> getRevenueChart(@AuthenticationPrincipal Jwt jwt) {
        return orderRepository.getRevenueByMonth(jwt.getSubject());
    }

    @GetMapping("/category-revenue")
    public List<TopCategoryDTO> getCategoryRevenue(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "period", defaultValue = "all") String period,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        String sellerId = jwt.getSubject();
        if ("custom".equals(period)) {
            try {
                LocalDate start = LocalDate.parse(startDate);
                LocalDate end = LocalDate.parse(endDate);
                if (end.isBefore(start)) {
                    throw new AppException(ErrorCode.VALIDATION_ERROR);
                }
            } catch (RuntimeException e) {
                if (e instanceof AppException appException) {
                    throw appException;
                }
                throw new AppException(ErrorCode.VALIDATION_ERROR);
            }
            return orderRepository.getRevenueByCategoryAndCustomRange(
                    sellerId, startDate + " 00:00:00", endDate + " 23:59:59");
        }
        if (!List.of("all", "month", "quarter", "year").contains(period)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return orderRepository.getRevenueByCategory(sellerId, period);
    }

    @GetMapping("/stats")
    public List<Map<String, Object>> getStatCards(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "period", defaultValue = "year") String period) {
        if (!List.of("today", "7days", "30days", "6months", "year", "all").contains(period)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }

        Map<String, Object> stats = orderRepository.getOverviewStats(jwt.getSubject(), period);
        Number revenue = numberValue(stats, "totalRevenue", "TOTALREVENUE");
        Number orders = numberValue(stats, "totalOrders", "TOTALORDERS");

        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(statCard("revenue", "Tổng Doanh Thu",
                revenue == null ? 0 : revenue.doubleValue(), "currency"));
        cards.add(statCard("orders", "Tổng Đơn Hàng",
                orders == null ? 0 : orders.longValue(), "number"));
        return cards;
    }

    @GetMapping("/order-status")
    public List<Map<String, Object>> getOrderStatus(@AuthenticationPrincipal Jwt jwt) {
        Map<String, String> colors = Map.of(
                "DELIVERED", "#10b981",
                "SHIPPING", "#a855f7",
                "CONFIRMED", "#3b82f6",
                "PENDING", "#eab308",
                "CANCELLED", "#ef4444");
        Map<String, String> names = Map.of(
                "DELIVERED", "Đã giao thành công",
                "SHIPPING", "Đang giao hàng",
                "CONFIRMED", "Đã xác nhận",
                "PENDING", "Chờ xử lý",
                "CANCELLED", "Đã hủy đơn");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : orderRepository.getOrderStatusCount(jwt.getSubject())) {
            String status = stringValue(row, "status", "STATUS");
            Number count = numberValue(row, "count", "COUNT");
            if (status != null && count != null) {
                result.add(Map.of(
                        "name", names.getOrDefault(status, status),
                        "value", count.longValue(),
                        "color", colors.getOrDefault(status, "#cbd5e1")));
            }
        }
        return result;
    }

    @GetMapping("/recent-orders")
    public List<Map<String, Object>> getRecentOrders(@AuthenticationPrincipal Jwt jwt) {
        String sellerId = jwt.getSubject();
        List<Order> orders = orderRepository.findRecentOrdersBySeller(sellerId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Order order : orders.stream().limit(8).toList()) {
            Map<String, Object> row = new HashMap<>();
            row.put("orderId", order.getId());
            row.put("customerName", order.getReceiverName() != null
                    ? order.getReceiverName()
                    : order.getUser().getUsername());
            row.put("customerAvatar", order.getUser().getAvatarUrl() != null
                    ? order.getUser().getAvatarUrl()
                    : "https://api.dicebear.com/9.x/notionists/svg?seed=" + order.getId().hashCode());

            double amount = 0;
            int itemCount = 0;
            for (OrderItem item : order.getItems()) {
                if (item.getProduct().getSeller() != null
                        && sellerId.equals(item.getProduct().getSeller().getId())) {
                    amount += item.getQuantity() * item.getUnitPrice();
                    itemCount += item.getQuantity();
                }
            }
            row.put("amount", amount);
            row.put("itemCount", itemCount);
            row.put("status", order.getStatus().name());
            row.put("date", getRelativeTime(order.getCreatedAt()));
            result.add(row);
        }
        return result;
    }

    @GetMapping("/recent-activities")
    public List<Map<String, Object>> getRecentActivities(@AuthenticationPrincipal Jwt jwt) {
        String sellerId = jwt.getSubject();
        List<Map<String, Object>> result = new ArrayList<>();
        long id = 1;

        List<Order> orders = orderRepository.findRecentOrdersBySeller(sellerId);
        for (Order order : orders.stream().limit(5).toList()) {
            String customer = order.getReceiverName() != null
                    ? order.getReceiverName()
                    : order.getUser().getUsername();
            Map<String, Object> activity = new HashMap<>();
            activity.put("id", id++);
            activity.put("timestamp", getRelativeTime(order.getCreatedAt()));
            activity.put("type", "order");
            activity.put("message", switch (order.getStatus()) {
                case PENDING -> "Đơn hàng mới #" + order.getId() + " được đặt bởi " + customer;
                case DELIVERED -> "Đơn hàng #" + order.getId() + " đã được giao thành công cho " + customer;
                case SHIPPING -> "Đơn hàng #" + order.getId() + " đang được vận chuyển tới " + customer;
                case CANCELLED -> "Đơn hàng #" + order.getId() + " bị hủy bởi " + customer;
                case CONFIRMED -> "Đơn hàng #" + order.getId() + " đã được xác nhận thành công";
            });
            activity.put("icon", order.getStatus() == OrderStatus.CANCELLED
                    ? "XCircle"
                    : order.getStatus() == OrderStatus.DELIVERED ? "CheckCircle2" : "ShoppingBag");
            activity.put("color", order.getStatus() == OrderStatus.CANCELLED
                    ? "rose"
                    : order.getStatus() == OrderStatus.DELIVERED ? "green" : "indigo");
            result.add(activity);
        }

        int lowStockCount = 0;
        for (Product product : productRepository.findBySellerIdAndIsDeletedFalse(sellerId)) {
            if (product.getStockQuantity() != null
                    && product.getStockQuantity() > 0
                    && product.getStockQuantity() < 5) {
                Map<String, Object> activity = new HashMap<>();
                activity.put("id", id++);
                activity.put("type", "product");
                activity.put("message", "Sản phẩm '" + product.getName()
                        + "' sắp hết hàng (chỉ còn " + product.getStockQuantity() + " chiếc)");
                activity.put("timestamp", "Hôm nay");
                activity.put("icon", "AlertCircle");
                activity.put("color", "orange");
                result.add(activity);
                if (++lowStockCount == 3) {
                    break;
                }
            }
        }
        return result;
    }

    private Map<String, Object> statCard(String id, String title, Number value, String type) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", id);
        card.put("title", title);
        card.put("value", value);
        card.put("type", type);
        return card;
    }

    private Number numberValue(Map<String, Object> row, String lowerCaseKey, String upperCaseKey) {
        if (row == null) {
            return null;
        }
        Object value = row.containsKey(lowerCaseKey) ? row.get(lowerCaseKey) : row.get(upperCaseKey);
        return value instanceof Number number ? number : null;
    }

    private String stringValue(Map<String, Object> row, String lowerCaseKey, String upperCaseKey) {
        Object value = row.containsKey(lowerCaseKey) ? row.get(lowerCaseKey) : row.get(upperCaseKey);
        return value instanceof String text ? text : null;
    }

    private String getRelativeTime(Instant instant) {
        if (instant == null) {
            return "Vừa xong";
        }
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return "Vừa xong";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " phút trước";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + " giờ trước";
        }
        return (seconds / 86400) + " ngày trước";
    }
}
