package com.greenharborlabs.jevlab.routing;

import java.util.Arrays;
import java.util.Optional;

/**
 * Request intents, most restrictive first. jev-1.13 leans toward the first Choice option, so this
 * order makes that bias err toward review.
 */
public enum Intent {
  ADMIN_OR_DESTRUCTIVE(
      "admin_or_destructive",
      "Deletes data, changes permissions or roles, or performs an administrative operation"),
  PAYMENT_OR_CREDENTIAL(
      "payment_or_credential",
      "Creates or pays invoices, moves money, or creates, rotates or reads keys or credentials"),
  WRITE("write", "Creates or updates ordinary user data without deleting it"),
  READ("read", "Only reads data and changes nothing"),
  OTHER("other", "None of the above, or not an API operation");

  private final String wireName;
  private final String description;

  Intent(String wireName, String description) {
    this.wireName = wireName;
    this.description = description;
  }

  public String wireName() {
    return wireName;
  }

  public String description() {
    return description;
  }

  static Optional<Intent> fromWire(String wireName) {
    return Arrays.stream(values()).filter(i -> i.wireName.equals(wireName)).findFirst();
  }
}
