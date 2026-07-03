package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.ApiResponse;
import com.vy.sales.inventory.entity.Product;
import com.vy.sales.inventory.service.ProductService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/inventory-svc/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

  private final ProductService service;

  @PostMapping
  public Mono<ResponseEntity<ApiResponse<Product>>> create(@RequestBody Product product) {

    log.info("Creating product name={}, sku={}", product.getName(), product.getSku());

    return service
        .create(product)
        .map(
            saved -> {
              log.info("Product created successfully id={}, sku={}", saved.getId(), saved.getSku());
              return ResponseEntity.status(HttpStatus.CREATED)
                  .body(new ApiResponse<>(true, saved, null));
            });
  }

  @PostMapping("/bulk")
  public Mono<ResponseEntity<ApiResponse<List<Product>>>> createBulk(
      @RequestBody List<Product> products) {

    log.info("Received request to create bulk products. Total products: {}", products.size());

    return service
        .createBulk(products)
        .collectList()
        .doOnNext(
            savedProducts ->
                log.info(
                    "Bulk products creation completed successfully. Total saved: {}",
                    savedProducts.size()))
        .doOnError(
            error ->
                log.error(
                    "Error occurred while creating bulk products: {}", error.getMessage(), error))
        .map(
            savedProducts ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(new ApiResponse<>(true, savedProducts, null)));
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Product>>> get(@PathVariable Long id) {

    log.info("Fetching product with id={}", id);

    return service
        .get(id)
        .map(product -> ResponseEntity.ok(new ApiResponse<>(true, product, null)));
  }

  @GetMapping
  public Mono<ResponseEntity<ApiResponse<List<Product>>>> getAll() {

    log.info("Fetching all products");

    return service
        .getAll()
        .collectList()
        .map(
            products -> {
              log.info("Fetched {} products successfully", products.size());
              return ResponseEntity.ok(new ApiResponse<>(true, products, null));
            });
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Product>>> update(
      @PathVariable Long id, @RequestBody Product product) {

    log.info("Updating product id={}, name={}, sku={}", id, product.getName(), product.getSku());

    return service
        .update(id, product)
        .map(
            updated -> {
              log.info("Product updated successfully id={}", id);
              return ResponseEntity.ok(new ApiResponse<>(true, updated, null));
            });
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Void>>> delete(@PathVariable Long id) {

    log.info("Deleting product with id={}", id);

    return service
        .delete(id)
        .then(
            Mono.fromSupplier(
                () -> {
                  log.info("Product deleted successfully id={}", id);
                  return ResponseEntity.ok(
                      new ApiResponse<>(true, null, "Product deleted successfully"));
                }));
  }

  @GetMapping("/search")
  public Mono<ResponseEntity<ApiResponse<List<Product>>>> search(
      @RequestParam(value = "categories", required = false) List<String> categories,
      @RequestParam(value = "name", required = false) String name,
      @RequestParam(value = "sku", required = false) String sku,
      @RequestParam(value = "isActive", required = false) Boolean isActive,
      @RequestParam(value = "createdBefore", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime createdBefore,
      @RequestParam(value = "createdAfter", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime createdAfter) {

    log.info(
        "Product search request categories={}, name={}, sku={}, isActive={}, createdBefore={}, createdAfter={}",
        categories,
        name,
        sku,
        isActive,
        createdBefore,
        createdAfter);

    return service
        .searchProducts(categories, name, sku, isActive, createdBefore, createdAfter)
        .collectList()
        .map(products -> ResponseEntity.ok(new ApiResponse<>(true, products, null)));
  }
}
