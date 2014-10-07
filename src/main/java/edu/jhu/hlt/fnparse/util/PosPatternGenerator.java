package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * This class extracts features from POS tag sequences like:
 * "DT JJ NNS NNS" => "D J N+"
 * 
 * @author travis
 */
public class PosPatternGenerator {
  public static final String LEFT_BOUNDARY = "B_start";
  public static final String RIGHT_BOUNDARY = "B_end";

  public static enum Mode {
    FULL_POS,
    COARSE_POS,  // first letter of POS
    WORD_SHAPE   // see shapeNormalize
  }

  private int tagsLeft = 1;
  private int tagsRight = 1;
  private Mode mode;

  private static String shapeNormalize(String s) {
    return s.replaceAll("[A-Z]", "X")
        .replaceAll("[a-z]", "x")
        .replaceAll("\\d", "0")
        .replaceAll("X{4,}", "X+")
        .replaceAll("X{3}", "X3")
        .replaceAll("X{2}", "X2")
        .replaceAll("x{4,}", "x+")
        .replaceAll("x{3}", "x3")
        .replaceAll("x{2}", "x2")
        .replaceAll("0{5,}", "0+");
  }

  public PosPatternGenerator(int tagsLeft, int tagsRight, Mode mode) {
    this.tagsLeft = tagsLeft;
    this.tagsRight = tagsRight;
    this.mode = mode;
  }

  public String extract(Span span, Sentence s) {
    return extract(span.start, span.end, s);
  }

  /**
   * @param start inclusive
   * @param end exclusive
   */
  public String extract(int start, int end, Sentence s) {
    TagCapture cap = new TagCapture();
    List<String> tags = new ArrayList<>();

    // Take this.tagsLeft from the left of the span
    if (tagsLeft > 0) {
      int added = 0;
      for (int i = start - 1; i >= -1 && added < tagsLeft; i--) {
        String t = cap.update(getTag(i, s));
        if (t != null) {
          tags.add(t);
          added++;
        }
      }
      if (added < tagsLeft) {
        String t = cap.update(null);
        assert t != null;
        tags.add(t);
      }
      Collections.reverse(tags);
      tags.add("[");
      cap.clear();
    }

    // Take the tags in the span
    for (int i = start; i < end; i++) {
      String t = cap.update(getTag(i, s));
      if (t != null) tags.add(t);
    }

    // Take this.tagsRight from the right of the span
    if (tagsRight > 0) {
      tags.add("]");
      cap.clear();
      int added = 0;
      for (int i = end; i <= s.size() && added < tagsRight; i++) {
        String t = cap.update(getTag(i, s));
        if (t != null) {
          tags.add(t);
          added++;
        }
      }
      if (added < tagsLeft) {
        String t = cap.update(null);
        assert t != null;
        tags.add(t);
      }
    }

    // Join the tokens together
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tags.size(); i++) {
      sb.append(tags.get(i));
      //if (i > 0) sb.append("_");
    }
    return sb.toString();
  }

  private String getTag(int i, Sentence s) {
    if (i < 0)
      return LEFT_BOUNDARY;
    if (i >= s.size())
      return RIGHT_BOUNDARY;
    if (mode == Mode.FULL_POS)
      return s.getPos(i);
    if (mode == Mode.COARSE_POS)
      return s.getPos(i).substring(0, 1);
    if (mode == Mode.WORD_SHAPE)
      return shapeNormalize(s.getWord(i));
    throw new RuntimeException();
  }

  /**
   * Handles the code which converts repeated elements in a sequence into
   * a single token, e.g. "A A" => "A+", "A B" => "A B".
   */
  static class TagCapture {
    private String tag = null;
    private int count = 0;

    /**
     * Clear the state of the capture.
     */
    public void clear() {
      tag = null;
      count = 0;
    }

    /**
     * Returns the string to be emitted if tag differs from the state of
     * this capture.
     */
    public String update(String tag) {
      if (tag != null && tag.equals(this.tag)) {
        count++;
        return null;
      } else {
        String ret = this.tag;
        if (this.count > 1)
          ret += "+";
        this.tag = tag;
        this.count = 0;
        return ret;
      }
    }
  }
}
