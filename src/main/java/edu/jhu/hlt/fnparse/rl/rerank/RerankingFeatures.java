package edu.jhu.hlt.fnparse.rl.rerank;

import edu.jhu.gm.feat.FeatureVector;

class RerankingFeatures {
  public void featurize(FeatureVector fv, DecoderState s, Item i) {
    // Does this item overlap with a previously-committed-to item? Its score?
    // How many items have been committed to for this frame instance?
    // How many items have been committed to for this (frame,role)?
    // Set of roles committed to for this frame (unigrams, pairs, and count)
    // Does this item overlap with a target?
    // Number of items committed to on this side of the target.
    throw new RuntimeException("implement me");
  }
}