package vn.vnpt.order.application.port;

import vn.vnpt.order.infrastructure.entity.OrderStateTransition;

/**
 * Port for appending a transition row — Story 4.1. Concrete impl in
 * {@code infrastructure/outbox/OrderModulithOutboxPublisher} (the Modulith outbox bridges both
 * the DB write and the in-process event publication).
 */
public interface OrderTransitionAppender {

  /** Returns the row ID of the inserted transition. */
  long append(OrderStateTransition transition);
}