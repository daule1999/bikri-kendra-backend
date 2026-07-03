package com.vy.printer.printer_service.repository;

import com.vy.printer.printer_service.model.Printer;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PrinterRepository extends ReactiveCrudRepository<Printer, Long> {

  Flux<Printer> findByOwnerAgentId(String ownerAgentId);

  Flux<Printer> findByEnabledTrue();

  @Query("SELECT * FROM printers WHERE owner_agent_id = :agentId AND is_default = TRUE LIMIT 1")
  Mono<Printer> findDefaultForAgent(String agentId);

  @Query("SELECT * FROM printers WHERE is_default = TRUE AND enabled = TRUE LIMIT 1")
  Mono<Printer> findGlobalDefault();
}
