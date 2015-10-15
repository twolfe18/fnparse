package edu.jhu.hlt.fnparse.features.precompute;

import edu.jhu.util.hash.MurmurHash3;

public class ProductIndex {

  public static final int SEED = 9001;
  public static final ProductIndex NIL = new ProductIndex(0, 1, 0, 0);

  private long featProd, cardProd;
  private int feat, card;
  private int arity;    // cache/memo, makes a few things O(1) instead of LL traversal
  private ProductIndex derivedFrom;

  private ProductIndex(int feat, int card, ProductIndex derivedFrom) {
    this.feat = feat;
    this.card = card;
    this.featProd = feat + derivedFrom.featProd * card;
    this.cardProd = derivedFrom.cardProd * card;
    this.derivedFrom = derivedFrom;
    this.arity = derivedFrom.arity + 1;
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

  public int getFeature() {
    return feat;
  }

  /**
   * Computes murmurhash on a sequence of the features with the product
   * cardinality on the end.
   * NOTE: May return a negative value!
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

  public ProductIndex prod(int feat, int card) {
    return new ProductIndex(feat, card, this);
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
