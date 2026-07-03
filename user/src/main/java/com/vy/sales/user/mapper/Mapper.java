package com.vy.sales.user.mapper;

import com.vy.sales.user.dto.SeatByCounterRequest;
import com.vy.sales.user.dto.UserRegistrationRequest;
import com.vy.sales.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class Mapper {
  public SeatByCounterRequest mapFromUserRegReqToSeatByCounterReq(
      UserRegistrationRequest userdto, User user) {
    SeatByCounterRequest request =
        SeatByCounterRequest.builder()
            .counterNumber(userdto.getCounterNumber())
            .userId(user.getId())
            .build();
    return request;
  }
}
