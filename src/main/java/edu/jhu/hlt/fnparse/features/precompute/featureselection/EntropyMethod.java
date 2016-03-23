package edu.jhu.hlt.fnparse.features.precompute.featureselection;

public enum EntropyMethod {
  BUB,    // use the method described by Paninski (2003)
  MLE,    // use raw counts to estimate H(X) and H(Y,X)
  MAP,    // use MAP estimates under Dirichlet-Multinomial for H(X) and H(Y,X)
}