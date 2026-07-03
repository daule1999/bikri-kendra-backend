package com.vy.sales.sales_service.mapper;

import com.vy.sales.sales_service.dto.BillingInvoiceRequest;
import com.vy.sales.sales_service.dto.CreateInvoiceRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class BillingInvoiceMapper {

  public CreateInvoiceRequest toBillingRequest(BillingInvoiceRequest salesRequest, Long billedBy) {

    CreateInvoiceRequest billing = new CreateInvoiceRequest();

    billing.setSalesOrderNumber(salesRequest.getSalesOrderNumber());
    billing.setShopId(salesRequest.getShopId());
    billing.setEventId(salesRequest.getEventId()); // propagate event scope
    billing.setPreGeneratedInvoiceNo(salesRequest.getPreGeneratedInvoiceNo());

    billing.setSellerId(salesRequest.getSeller().getId());
    billing.setSellerName(salesRequest.getSeller().getName());

    billing.setBilledBy(billedBy);

    if (salesRequest.getCustomer() != null) {
      billing.setCustomerName(salesRequest.getCustomer().getName());
      billing.setCustomerMobile(salesRequest.getCustomer().getMobile());
    }

    billing.setSubtotalAmount(salesRequest.getSubtotalAmount());
    billing.setDiscountAmount(salesRequest.getDiscountAmount());

    // 👉 Billing owns tax & net calculation
    billing.setTaxAmount(BigDecimal.ZERO);
    billing.setNetAmount(
        salesRequest.getSubtotalAmount().subtract(salesRequest.getDiscountAmount()));

    billing.setItems(salesRequest.getItems().stream().map(this::toBillingItem).toList());

    return billing;
  }

  private CreateInvoiceRequest.InvoiceItemRequest toBillingItem(BillingInvoiceRequest.Item item) {
    CreateInvoiceRequest.InvoiceItemRequest billingItem =
        new CreateInvoiceRequest.InvoiceItemRequest();

    billingItem.setProductId(item.getProductId());
    billingItem.setProductName(item.getProductName());
    billingItem.setProductSku(item.getProductSku());
    billingItem.setHsnCode(item.getHsnCode());
    billingItem.setQuantity(item.getQuantity());
    billingItem.setUnitPrice(item.getUnitPrice());
    billingItem.setDiscount(item.getDiscount());

    // Billing responsibility
    billingItem.setTaxRate(BigDecimal.ZERO);
    billingItem.setTaxAmount(BigDecimal.ZERO);
    billingItem.setLineTotal(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

    return billingItem;
  }
}
