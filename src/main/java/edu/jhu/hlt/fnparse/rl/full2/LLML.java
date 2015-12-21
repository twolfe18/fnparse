package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;

/**
 * Sum of any {@link MaxLoss}s in the list.
 *
 * @author travis
 */
public class LLML<T extends HasMaxLoss> extends LL<T> implements HasMaxLoss {

  private final MaxLoss sumLoss;

  public LLML(T item, LLML<T> next) {
    super(item, next);
    if (next == null)
      sumLoss = item.getLoss();
    else
      sumLoss = MaxLoss.sum(item.getLoss(), cdr().getLoss());
  }

  @Override
  public MaxLoss getLoss() {
    return sumLoss;
  }

  @Override
  public LLML<T> cdr() {
    return (LLML<T>) next;
  }

}