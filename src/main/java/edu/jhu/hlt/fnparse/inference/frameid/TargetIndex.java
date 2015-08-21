package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.tutils.data.WordNetPosUtil;

/**
 * Identifies frames that are possible at a given location in a sentence.
 * 
 * @author travis
 */
public class TargetIndex {
  public static final Logger LOG = Logger.getLogger(TargetIndex.class);

  private static TargetIndex singleton;

  public static TargetIndex getInstance() {
    if (singleton == null)
      singleton = new TargetIndex();
    return singleton;
  }

  private static class LuMatcher {
    private String[] words;
    private boolean[] inParens;
    private List<String> ptbPos;

    public LuMatcher(String[] words, boolean[] inParens, String fnPos) {
      if (words.length != inParens.length)
        throw new IllegalArgumentException();
      assert inParens.length > 0 && !inParens[0];
      this.words = words;
      this.inParens = inParens;
      fnPos = fnPos.toUpperCase();
      this.ptbPos = WordNetPosUtil.getFrameNetPosToAllPennTags().get(fnPos);
      if (ptbPos == null)
        throw new RuntimeException();
      if (words.length == 1 && "the".equals(words[0]))
        throw new RuntimeException();
    }

    /**
     * Returns null if it doesn't match
     * @param requirePosMatchOneSingleWord - slightly higher performance with
     *        crappy features if true, higher recall if false
     */
    public Span matches(
        int i,
        Sentence s,
        boolean requireInParens,
        boolean ignorePos) {
      // Check the words are the same
      int end = i;
      for (int offset = 0; offset < words.length; offset++) {
        if (!requireInParens && inParens[offset])
          continue;
        // TODO look words up in wordnet to generalize over morphology
        int j = i + offset;
        if (j >= s.size())
          return null;

        boolean wordMatch = words[offset].equalsIgnoreCase(s.getWord(j))
            || words[offset].equalsIgnoreCase(s.getLemma(j));
        boolean posMatch = ignorePos || ptbPos.contains(s.getPos(j));
        if (wordMatch && posMatch) {
          end++;
        } else {
          return null;
        }
      }
      return Span.getSpan(i, end);
    }

    public static Map<Frame, List<LuMatcher>> getLuMatchersFromLuIndex() {
      File f = new File("toydata/fn15-frameindexLU");
      if (!f.isFile()) throw new RuntimeException();
      try {
        Map<Frame, List<LuMatcher>> f2lum = new HashMap<>();
        BufferedReader r = new BufferedReader(
            new InputStreamReader(new FileInputStream(f)));
        // Discard first line
        String firstLine = r.readLine();
        assert "frameserialid\tframexmlid\tframename\tluname".equals(firstLine);
        while (r.ready()) {
          String[] toks = r.readLine().split("\t");
          String frameName = toks[2];
          String lu = toks[3];
          // Strip off any quotations
          lu = lu.replaceAll("\"|'", "");
          // Poorly annotated case
          if ("(in/out) line.n".equals(lu))
            lu = "in line.n";
          // Parse out the POS
          int dot = lu.lastIndexOf('.');
          String pos = lu.substring(dot + 1);
          lu = lu.substring(0, dot);
          // If you see double parens, then drop it
          // e.g. "protection_((entity)).n" -> "protection.n"
          lu = lu.replaceFirst("_\\(\\(.+\\)\\)$", "");
          // If you see single parens, then the thing in the parens is usually
          // a preposition or particle, and should be matched.
          // Not matching often changes the meaning completely
          // e.g. "Expertise new_(to).a" or "Personal_relationship sleep_(with).v"
          // BUT not always...
          // "Taking_sides believe_(in).v" and "Forming_relationships marry_(into).v"
          boolean ip = false;
          String[] words = lu.split("_|\\s");
          boolean[] inParens = new boolean[words.length];
          for (int i = 0; i < words.length; i++) {
            if (words[i].startsWith("(")) {
              ip = true;
              words[i] = words[i].substring(1);
            }
            inParens[i] = ip;
            if (words[i].endsWith(")")) {
              ip = false;
              words[i] = words[i].substring(0, words[i].length() - 1);
            }
          }
          // Store the LuMatcher
          LuMatcher lum = new LuMatcher(words, inParens, pos);
          Frame frame = FrameIndex.getFrameNet().getFrame(frameName);
          addlum(f2lum, frame, lum);
        }
        r.close();

        // Special cases that are common in training data
        addlum(f2lum,
            FrameIndex.getFrameNet().getFrame("Existence"),
            new LuMatcher(new String[] {"there"}, new boolean[] {false}, "n"));
        addlum(f2lum,
            FrameIndex.getFrameNet().getFrame("Quantity"),
            new LuMatcher(new String[] {"lot"}, new boolean[] {false}, "n"));
        addlum(f2lum,
            FrameIndex.getFrameNet().getFrame("Intentionally_act"),
            new LuMatcher(new String[] {"carry"}, new boolean[] {false}, "v"));

        return f2lum;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static void addlum(
      Map<Frame, List<LuMatcher>> lums, Frame f, LuMatcher lum) {
    List<LuMatcher> l = lums.get(f);
    if (l == null) {
      l = new ArrayList<>();
      l.add(lum);
      lums.put(f, l);
    } else {
      l.add(lum);
    }
  }

  private Map<Frame, List<LuMatcher>> matchers =
      LuMatcher.getLuMatchersFromLuIndex();

  public Map<Span, Set<Frame>> findFrames(
      Sentence s,
      boolean requireInParens,
      boolean ignorePos) {
    Map<Span, Set<Frame>> byTarget = new HashMap<>();
    for (Map.Entry<Frame, List<LuMatcher>> x : matchers.entrySet()) {
      Frame f = x.getKey();
      for (LuMatcher m : x.getValue()) {
        for (int i = 0; i < s.size(); i++) {
          Span t = m.matches(i, s, requireInParens, ignorePos);
          if (t != null) {
            assert t.start >= 0 && t.end <= s.size();
            Set<Frame> possible = byTarget.get(t);
            if (possible == null) {
              possible = new HashSet<>();
              possible.add(f);
              byTarget.put(t, possible);
            } else {
              possible.add(f);
            }
          }
        }
      }
    }
    return byTarget;
  }
}
