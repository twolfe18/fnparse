package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.concrete.UUID;

/**
 * Wrapper around {@link UUID} which properly implements hashCode and equals
 * and memoizes the hashCode.
 *
 * @author travis
 */
public class HashableUUID {

  private UUID uuid;
  private int hc;

  public HashableUUID(UUID uuid) {
    this.uuid = uuid;
    this.hc = uuid.getUuidString().hashCode();
  }

  public UUID get() {
    return uuid;
  }

  @Override
  public int hashCode() {
    return hc;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof HashableUUID) {
      HashableUUID hu = (HashableUUID) other;
      return uuid.equals(hu.uuid);
    }
    return false;
  }
}
