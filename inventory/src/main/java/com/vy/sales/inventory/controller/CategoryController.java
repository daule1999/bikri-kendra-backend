package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.ApiResponse;
import com.vy.sales.inventory.entity.Category;
import com.vy.sales.inventory.exceptions.CategoryNotFoundException;
import com.vy.sales.inventory.service.CategoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/inventory-svc/categories")
@RequiredArgsConstructor
@Slf4j
public class CategoryController {

  private final CategoryService service;

  @PostMapping
  public Mono<ResponseEntity<ApiResponse<Category>>> create(@RequestBody Category category) {
    log.info("Creating category with name={}", category.getName());

    return service
        .create(category)
        .map(
            saved -> {
              log.info("Category created successfully with id={}", saved.getId());
              return ResponseEntity.ok(new ApiResponse<>(true, saved, null));
            })
        .onErrorResume(
            ex -> {
              String errorMsg =
                  (ex instanceof org.springframework.dao.DataIntegrityViolationException)
                      ? "Category already exists or invalid reference."
                      : ex.getMessage();

              log.error("Failed to create category with name={}", category.getName(), ex);

              return Mono.just(
                  ResponseEntity.badRequest().body(new ApiResponse<>(false, null, errorMsg)));
            });
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Category>>> get(@PathVariable Long id) {
    log.info("Fetching category with id={}", id);

    return service
        .getById(id)
        .map(category -> ResponseEntity.ok(new ApiResponse<>(true, category, null)))
        .onErrorResume(
            ex -> {
              if (ex instanceof CategoryNotFoundException) {
                return Mono.just(
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ApiResponse<>(false, null, ex.getMessage())));
              }

              log.error("Failed to fetch category with id={}", id, ex);

              return Mono.just(
                  ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                      .body(new ApiResponse<>(false, null, "Unexpected error occurred")));
            });
  }

  @GetMapping
  public Mono<ResponseEntity<ApiResponse<List<Category>>>> getAll() {

    log.info("Fetching all categories");

    return service
        .getAll()
        .collectList()
        .map(
            categories -> {
              log.info("Fetched {} categories successfully", categories.size());
              return ResponseEntity.ok(new ApiResponse<>(true, categories, null));
            });
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Category>>> update(
      @PathVariable Long id, @RequestBody Category category) {

    log.info("Updating category id={} with name={}", id, category.getName());

    return service
        .update(id, category)
        .map(
            updated -> {
              log.info("Category updated successfully with id={}", id);
              return ResponseEntity.ok(new ApiResponse<>(true, updated, null));
            });
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Void>>> delete(@PathVariable Long id) {

    log.info("Deleting category with id={}", id);

    return service
        .delete(id)
        .then(
            Mono.fromSupplier(
                () -> {
                  log.info("Category deleted successfully with id={}", id);
                  return ResponseEntity.ok(
                      new ApiResponse<>(true, null, "Category deleted successfully"));
                }));
  }
}
