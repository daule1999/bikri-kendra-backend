package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.Event;
import java.util.List;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface EventRepository extends ReactiveCrudRepository<Event, Long> {

  @Query("SELECT * FROM event WHERE id IN (:ids)")
  Flux<Event> findByIdIn(List<Long> ids);
}
