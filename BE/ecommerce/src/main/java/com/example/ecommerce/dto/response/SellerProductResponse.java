package com.example.ecommerce.dto.response;

import com.example.ecommerce.enums.ProductStatus;
import lombok.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SellerProductResponse {

    private Long id;
    @NotBlank
    private String name;

    private String imageUrl;
    private List<String> images;

    private String itemNo;
    private String scale;
    private String marque;
    private ProductStatus status;

    private String description;
    @NotNull
    @PositiveOrZero
    private Double price;
    private Integer soldQuantity;
    @NotNull
    @PositiveOrZero
    private Integer stockQuantity;
    private Boolean isAuction;
    private Boolean isDeleted;
    private String sellerId;
    private String sellerName;
    private String sellerEmail;
    private String sellerPhoneNumber;
    private String sellerAvatar;
    private Boolean isApproved;
    private BigDecimal auctionStartPrice;
    private LocalDateTime auctionStartTime;
    private LocalDateTime auctionEndTime;
    private BigDecimal currentPrice;
    private Boolean auctionPaid;
}
