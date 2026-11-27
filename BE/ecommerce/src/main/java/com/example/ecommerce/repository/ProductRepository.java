package com.example.ecommerce.repository;

import com.example.ecommerce.entity.Product;
import com.example.ecommerce.enums.ProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :productId")
    java.util.Optional<Product> findByIdForUpdate(@Param("productId") Long productId);

    List<Product> findByIsDeletedFalseAndIsApprovedTrue();

    List<Product> findByIsAuctionAndIsDeletedFalseAndIsApprovedTrue(Boolean isAuction);

    List<Product> findByIsAuctionTrueAndStatusInAndIsApprovedTrueAndIsDeletedFalse(List<ProductStatus> statuses);

    List<Product> findByIsAuctionTrueAndStatusAndAuctionPaidFalseAndIsApprovedTrueAndIsDeletedFalse(ProductStatus status);

    List<Product> findByIsDeletedFalseAndIsApprovedTrue(Sort sort);

    List<Product> findBySellerIdAndIsDeletedFalse(String sellerId);

    List<Product> findByIsDeletedFalseAndIsApprovedFalse();

    List<Product> findBySellerIdAndIsAuctionAndIsDeletedFalse(
            String sellerId,
            Boolean isAuction
    );

    List<Product> findBySellerIdAndIsDeletedFalse(
            String sellerId,
            Sort sort
    );

    List<Product> findByIsDeletedFalse();

    @Query("""
    SELECT p
    FROM Product p
    WHERE LOWER(p.name)
          LIKE LOWER(CONCAT('%', :keyword, '%'))
""")
    List<Product> searchSuggestions(
            @Param("keyword") String keyword,
            Pageable pageable
    );

}
