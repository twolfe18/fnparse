package edu.jhu.hlt.uberts.auto;

import edu.jhu.hlt.tutils.hash.Hash;

public class IndexedRule {

  public final int index;
  public final Rule rule;

  public IndexedRule(int index, Rule rule) {
    this.index = index;
    this.rule = rule;
  }

  @Override
  public int hashCode() {
    return Hash.mix(9001, index);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof IndexedRule) {
      IndexedRule ir = (IndexedRule) other;
      if (ir.index == index) {
        assert ir.rule == rule;
        return true;
      }
      return false;
    }
    return false;
  }
}
