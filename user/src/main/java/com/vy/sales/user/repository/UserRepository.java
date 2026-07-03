package com.vy.sales.user.repository;

import com.vy.sales.user.entity.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<User, Long> {

  Mono<User> findByUsername(String username);

  Mono<User> findByEmail(String email);

  Mono<User> findByMobile(String mobile);

  Mono<Boolean> existsByUsername(String username);

  Mono<Boolean> existsByEmail(String email);

  Mono<Boolean> existsByMobile(String mobile);
}
