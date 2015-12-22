package edu.jhu.hlt.fnparse.rl.full2;

public class LL<T> {

  protected final T item;
  protected final LL<T> next;
  protected final int length;

  public LL(T item, LL<T> next) {
    this.item = item;
    this.next = next;
    this.length = next == null ? 1 : next.length + 1;
  }

  @Override
  public String toString() {
    return item + " -> " + next;
  }

  public T car() {
    return item;
  }

  public LL<T> cdr() {
    return next;
  }

  public int length() {
    return length;
  }
  public static int length(LL<?> l) {
    if (l == null)
      return 0;
    return l.length;
  }

  /* THESE AREN'T GOING TO WORK FOR SUB-TYPES OF LL ***************************/
  public LL<T> remove(T search) {
    return replace(search, null);
  }

  /** If replace is null, this method will remove search */
  public LL<T> replace(T search, T replace) {
    if (item == search) {
      return replace == null ? next : new LL<>(replace, next);
//      return replace == null ? next : next.prepend(replace);
    } else {
      return next == null ? null : new LL<>(item, next.replace(search, replace));
//      return next == null ? null : next.replace(search, replace).prepend(item);
    }
  }

  public LL<T> prepend(T item) {
    return new LL<>(item, this);
  }
}
