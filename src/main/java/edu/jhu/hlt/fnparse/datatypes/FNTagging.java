package edu.jhu.hlt.fnparse.datatypes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.util.HasId;
import edu.jhu.hlt.fnparse.util.HasSentence;

/**
 * A Sentence with Frame targets tagged, but no arguments (necessarily) labeled.
 * If you necessarily have arguments tagged, use FNParse.
 * This class is mainly used by parsers that take a two step approach to
 * parsing: predict frame targets and then predict their arguments.
 * 
 * @author travis
 */
public class FNTagging implements HasId, HasSentence, Serializable {
  private static final long serialVersionUID = 6732344909323712341L;

  protected Sentence sent;
  protected List<FrameInstance> frameInstances;

  public FNTagging(Sentence s, List<FrameInstance> frameMentions) {
    if(frameMentions == null || s == null)
      throw new IllegalArgumentException();
    this.sent = s;
    this.frameInstances = new ArrayList<>();
    Map<Span, FrameInstance> seenTargets = new HashMap<>();
    for(FrameInstance fi : frameMentions) {
      FrameInstance collision = seenTargets.get(fi.getTarget());
      if (collision != null) {
        //Log.info("target collision in " + s.getId() + "@" + fi.getTarget()
        //    + ":" + collision + ", " + fi);
        // Keep the one with more arguments
        if (fi.numRealizedArguments() > collision.numRealizedArguments()) {
          seenTargets.put(fi.getTarget(), fi);
          int i = frameInstances.indexOf(collision);
          this.frameInstances.set(i, fi);
        }
      } else {
        seenTargets.put(fi.getTarget(), fi);
        this.frameInstances.add(fi);
      }
    }
  }

  @Override
  public String toString() {
    String className = this.getClass().getName().replace(
        "edu.jhu.hlt.fnparse.datatypes.", "");
    StringBuilder sb = new StringBuilder();
    sb.append("<");
    sb.append(className);
    sb.append(" of ");
    sb.append(sent.getId());
    for(FrameInstance fi : frameInstances) {
      sb.append(" ");
      sb.append(fi.toString());
    }
    sb.append(">");
    return sb.toString();
  }

  @Override
  public Sentence getSentence() { return sent; }

  public List<FrameInstance> getFrameInstances() { return frameInstances; }

  public FrameInstance getFrameInstance(int i) { return frameInstances.get(i); }

  public int numFrameInstances() { return frameInstances.size(); }

  public Map<Span, FrameInstance> getFrameLocations() {
    Map<Span, FrameInstance> goldFrames = new HashMap<Span, FrameInstance>();
    for(FrameInstance fi : frameInstances)
      goldFrames.put(fi.getTarget(), fi);
    return goldFrames;
  }

  @Override
  public String getId() { return sent.getId(); }

  @Override
  public int hashCode() {
    return sent.hashCode() ^ (1 << frameInstances.size());
  }

  @Override
  public boolean equals(Object other) {
    if(other instanceof FNTagging) {
      FNTagging t = (FNTagging) other;
      return sent.equals(t.sent) && frameInstances.equals(t.frameInstances);
    }
    else return false;
  }
}
