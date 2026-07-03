package com.vy.sales.inventory.repository;

import com.vy.sales.inventory.entity.Category;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface CategoryRepository extends ReactiveCrudRepository<Category, Long> {}
