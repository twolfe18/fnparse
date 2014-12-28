package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;

class DecoderState {
  FNTagging frames;
  List<Item> included;
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