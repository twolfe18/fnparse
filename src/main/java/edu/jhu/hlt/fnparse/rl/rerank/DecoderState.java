package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;

/**
 * @deprecated I'm going back to rl.State
 * @author travis
 */
class DecoderState {

  private FNTagging frames;
  private Item applied;
  private DecoderState prev;
  // TODO include some index on the applied items going back in time
  // this index should be designed just to serve the needs of the feature functions

  // Ah, this state is very close to the previous state I designed.
  // The current implementation in Reranker maintains a list of items that
  // can be committed to, but upon committing to an item, it never prunes
  // the remaining but incompatible items.
  // The first implementation does this by lazily generating all of the items
  // using the actions() method.

  /*
   * backing up to 10k feet again, what I've done:
   * I'm ignoring reinforcement learning because I'm confused about how you are
   * supposed to train these things. State space is too large and not terribly
   * meaningful.
   * I'm treating this as a larger structured prediction problem than it
   * originally was by reasoning over decodings as well as configurations.
   * I'm using very high arity factors and approximate inference (beam-search
   * for solving an argmax of a linear score function).
   */

  public DecoderState(FNTagging frames) {
    this.frames = frames;
    this.included = new ArrayList<>();
  }

  public void addItem(Item i) {
    included.add(i);
    // TODO may need other indexing operations here.
  }

  public FNParse getParse() {
    throw new RuntimeException("implement me");
  }
}