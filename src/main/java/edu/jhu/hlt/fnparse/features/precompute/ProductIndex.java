package edu.jhu.hlt.fnparse.features.precompute;

import java.io.Serializable;
import java.util.Comparator;

import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.hash.MurmurHash3;

public class ProductIndex implements Serializable {
  private static final long serialVersionUID = 8195810997213491777L;

  public static final int SEED = 9001;

  /** a.k.a. "One", multiplicative identity */
  public static final ProductIndex NIL = new ProductIndex(0, 1, 0, 0);

  public static final ProductIndex FALSE = new ProductIndex(0, 2);
  public static final ProductIndex TRUE = new ProductIndex(1, 2);

  /** Ignores cardinality */
  public static final Comparator<ProductIndex> BY_PROD_FEAT_ASC = new Comparator<ProductIndex>() {
    @Override
    public int compare(ProductIndex o1, ProductIndex o2) {
      long i1 = o1.getProdCardinality();
      long i2 = o2.getProdCardinality();
      if (i1 < i2)
        return -1;
      if (i1 > i2)
        return +1;
      return 0;
    }
  };

  private long featProd, cardProd;
  private long feat, card;
  private int arity;    // cache/memo, makes a few things O(1) instead of LL traversal
  private ProductIndex derivedFrom;

  private ProductIndex(long feat, long card, ProductIndex derivedFrom) {
    assert feat >= 0 && card >= 0;
    this.feat = feat;
    this.card = card;
    this.featProd = feat + derivedFrom.featProd * card;
    this.cardProd = derivedFrom.cardProd * card;
    this.derivedFrom = derivedFrom;
    this.arity = derivedFrom.arity + 1;
    assert featProd >= 0;
    assert cardProd >= 0;
  }

  public ProductIndex(int feat, int card, int thisIsTheNilConstructor, int foo) {
    this.feat = feat;
    this.card = card;
    this.featProd = feat;
    this.cardProd = card;
    this.derivedFrom = null;
    this.arity = 0;
  }

  public ProductIndex(int feat, int card) {
    this(feat, card, NIL);
  }

  public ProductIndex(int feat) {
    this(feat, 0, NIL);
  }

  public String toString() {
    if (this == NIL) {
      return "1";
    }
    return ("(Prod prod=" + featProd + "/" + cardProd + " feat=" + feat + "/" + card + " * " + derivedFrom.toString() + ")");
  }

  public long getProdFeature() {
    return featProd;
  }
  public int getProdFeatureSafe() {
    if (featProd > Integer.MAX_VALUE)
      throw new RuntimeException();
    return (int) featProd;
  }

  public long getProdCardinality() {
    return cardProd;
  }
  public int getProdCardinalitySafe() {
    if (cardProd > Integer.MAX_VALUE)
      throw new RuntimeException();
    return (int) cardProd;
  }

  public int getProdFeatureModulo(int modulus) {
    long m = modulus;
    long r = Math.floorMod(featProd, m);
    return (int) r;
  }

  public int getFeatureSafe() {
    int i = (int) feat;
    assert i >= 0;
    return i;
  }
  public long getFeature() {
    return feat;
  }

  public int getCardinalitySafe() {
    int i = (int) card;
    assert i >= 0;
    return i;
  }
  public long getCardinality() {
    return card;
  }

  /**
   * Computes murmurhash on a sequence of the features with the product
   * cardinality on the end.
   * NOTE: May return a negative value!
   * NOTE: Slow (compared to getProdFeature)!
   */
  public int getHashedProdFeature() {
    byte[] buf = new byte[(this.arity + 1) * 4];
    int i = 0;
    for (ProductIndex pi = this; pi != NIL; pi = pi.derivedFrom) {
      buf[i + 0] = (byte) (0xff & (pi.feat >>> 0));
      buf[i + 1] = (byte) (0xff & (pi.feat >>> 8));
      buf[i + 2] = (byte) (0xff & (pi.feat >>> 16));
      buf[i + 3] = (byte) (0xff & (pi.feat >>> 24));
      i += 4;
    }
    buf[i + 0] = (byte) (0xff & (cardProd >>> 0));
    buf[i + 1] = (byte) (0xff & (cardProd >>> 8));
    buf[i + 2] = (byte) (0xff & (cardProd >>> 16));
    buf[i + 3] = (byte) (0xff & (cardProd >>> 24));
    assert i + 4 == buf.length;
    return MurmurHash3.murmurhash3_x86_32(buf, 0, buf.length, SEED);
  }

  public int getHashedFasterProdFeature() {
    long h = Hash.mix64(feat, arity);
    for (ProductIndex p = derivedFrom; p != null; p = p.derivedFrom)
      h = Hash.mix64(h, p.feat);
    return (int) h;
  }

  /**
   * See unsafe version, but this will only return a value >= 0.
   * NOTE: You loose a bit of entropy.
   */
  public int getHashedProdFeatureNonNegative() {
    int h = getHashedProdFeature();
    return h < 0 ? ~h : h;
  }
  public int getHashedProdFeatureModulo(int modulus) {
    assert modulus > 0;
    return Math.floorMod(getHashedProdFeature(), modulus);
  }

  public int getArity() {
    int arity = 1;
    for (ProductIndex pi = derivedFrom; pi != NIL; pi = pi.derivedFrom)
      arity++;
    return arity;
  }

  public ProductIndex prod(boolean b) {
    return prod(b ? 1 : 0, 2);
  }

  public ProductIndex prod(long feat, long card) {
    assert feat < card && feat >= 0 : "feat=" + feat + " card=" + card;
    return new ProductIndex(feat, card, this);
  }

  /** You loose the chain in the argument, calls getProd(Feat|Card)Safe */
  public ProductIndex flatProd(ProductIndex other) {
    return prod(other.getProdFeature(), other.getProdCardinality());
  }

  /** Destroys cardinality, may be used once */
  public ProductIndex destructiveProd(long i) {
    assert i >= 0;
    assert cardProd > 0;
    long f = cardProd * i + featProd;
    return new ProductIndex(f, 0L, NIL);
  }

  public ProductIndex div() {
    return derivedFrom;
  }

  @Override
  public int hashCode() {
    return (int) featProd;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof ProductIndex) {
      ProductIndex x = (ProductIndex) other;
      return featProd == x.featProd && cardProd == x.card;
    }
    return false;
  }
}
