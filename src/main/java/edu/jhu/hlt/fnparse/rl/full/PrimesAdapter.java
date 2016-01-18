package edu.jhu.hlt.fnparse.rl.full;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.data.propbank.RoleType;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.rl.full.State.SpecialFrame;
import edu.jhu.hlt.fnparse.rl.full.State.SpecialRole;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.map.LongIntHashMap;

/**
 * Finds an index for every sub-set of (t,f,k,s) assignments via hashing.
 * There will be collisions either way, and I need something working now!
 */
public class PrimesAdapter implements Serializable {
  private static final long serialVersionUID = -3267402547765020302L;

  private Primes primes;
  private int nPrimes;
  private FrameRolePacking frp;

  // Optimization for assiging small primes:
  // As (t,f,k,s) come in, they are hashed, then looked up through this
  // map to get something in [0,n), which then map onto the first (smallest)
  // n primes, making signatures smaller/faster.
  private LongIntHashMap hash2dense;

  private static final int MISSING = -1;

    /*
     * sig is a product of primes.
     * we use integer multiplication because it is commutative, like set add.
     * it turns out that the elements in our set have some structure,
     *   specifically they are products of indices like t*f*k*s
     * but if our elements are represented as primes, we can't factor them
     * we could have chosen addition in the outer monoid:
     *   the only trouble is with addition:
     *   if we want to have the property that sig = list of monoid operations
     *   doesn't collide, then we need to ensure something like
     *   x + y == x + z => y == z
     *   with mult/primes this is easy: we can talk about prime factorizations of x+y or x+z, which are unique
     *   with addition, the naive thing to do is use powers of two... need T*K*F*S bits... too big
     *   with mult/primes (optimally), you need (less than) D*log(P) where D is the number of realized (t,f,k,s) and P is the Dth prime.
     *   e.g. D=15 => P=47 => D*log(P) = 84 bits...
     *   this bound is based on the trick that you can sort the (t,f,k,s) by some a priori score and assign small primes to things likely to be in the set (product)
     *   this does grow faster than I thought:
     *   awk 'BEGIN{p=1} {p *= $1; print NR, $1, log(p)/log(2)}' <(zcat primes1.byLine.txt.gz) | head -n 20
1 2 1
2 3 2.58496
3 5 4.90689
4 7 7.71425
5 11 11.1737
6 13 14.8741
7 17 18.9616
8 19 23.2095
9 23 27.7331
10 29 32.5911
11 31 37.5452
12 37 42.7547
13 41 48.1123
14 43 53.5385
15 47 59.0931
16 53 64.821
17 59 70.7037
18 61 76.6344
19 67 82.7005
20 71 88.8502
     * This means that even if you guess perfectly on your sort order,
     * you still can only fit 15 items in a uint64_t.
     *
     * The way I have it implemented (no sort over primes), the number of bits
     * needed is going to be something like 580 bits for 25 (t,f,k,s) and
     * 900 for 40. It should grow slightly faster than linear in nnz.
     *
     * zcat data/primes/primes1.byLine.txt.gz | shuf | awk 'BEGIN{p=1} {p *= $1; if (NR % 25 == 0) { print log(p)/log(2); p=1; }}' | head -n 1000 | plot
     */

  public PrimesAdapter(Primes p, FrameRolePacking frp) {
    this.primes = p;
    this.nPrimes = p.size();
    this.frp = frp;
    this.hash2dense = new LongIntHashMap(30 * 10 * 5 * 30, MISSING);
  }

  public Primes getPrimes() {
    return primes;
  }

  private int gp(long h) {
    assert h >= 0;
    int hd = hash2dense.get(h);
    if (hd == MISSING) {
      hd = hash2dense.size();
      hash2dense.put(h, hd);
    }
    return primes.get(hd % nPrimes);
  }

  public int get(Span t) {
    return gp(17 * index(t));
  }

  public int getSpecial(SpecialFrame f) {
    return gp(13 * (1 + f.ordinal()));
  }

  public int get(Span t, Frame f) {
    return gp(11 * index(t) * (f.getId() + 1));
  }

  public int getSpecial(Span t, Frame f, SpecialRole r) {
    return gp(7 * index(t) * (1 + f.getId()) * (1 + r.ordinal()));
  }

  public int get(Span t, Frame f, int k, RoleType q) {
    return gp(5 * index(t) * (1 + frp.index(f, k)) * (1 + q.ordinal()));
  }

  public int get(Span t, Frame f, Span s) {
    return gp(3 * index(t) * (1 + f.getId()) * index(s));
  }

  public int get(Span t, Frame f, int k, RoleType q, Span s) {
    return gp(2 * index(t) * (1 + frp.index(f, k)) * (1 + q.ordinal()) * index(s));
  }

  private static long index(Span span) {
    if (span == Span.nullSpan)
      return 1;
    return 2 + Span.index(span);
  }
}