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

import org.apache.jena.atlas.logging.Log;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Identifies frames that are possible at a given location in a sentence.
 * 
 * @author travis
 */
public class TargetIndex {

  private static class LuMatcher {
    public static final Logger LOG = Logger.getLogger(LuMatcher.class);
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
      this.ptbPos = PosUtil.getFrameNetPosToAllPennTags().get(fnPos);
      if (ptbPos == null)
        throw new RuntimeException();
      if (words.length == 1 && "the".equals(words[0]))
        throw new RuntimeException();
    }

    /**
     * Returns null if it doesn't match
     */
    public Span matches(int i, Sentence s, boolean requireInParens) {
      // Check the words are the same
      //IRAMDictionary dict = TargetPruningData.getInstance().getWordnetDict();
      //WordnetStemmer stemmer = TargetPruningData.getInstance().getStemmer();
      int end = i;
      for (int offset = 0; offset < words.length; offset++) {
        if (!requireInParens && inParens[offset])
          continue;
        // TODO look words up in wordnet to generalize over morphology
        int j = i + offset;
        if (j >= s.size())
          return null;

        if (words[offset].equalsIgnoreCase(s.getWord(j))
            || words[offset].equalsIgnoreCase(s.getLemma(j))) {
          end++;
        } else {
          return null;
        }
      }
      /*
      if (words.length == 1) {
        // Require the POS to match
        if (ptbPos.contains(s.getPos(i)))
          return Span.widthOne(i);
        else
          return null;
      }
      */
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
          if ("a lot.n".equals(lu))
            LOG.debug("check this");
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
          Frame frame = FrameIndex.getInstance().getFrame(frameName);
          List<LuMatcher> lums = f2lum.get(frame);
          if (lums == null) {
            lums = new ArrayList<>();
            lums.add(lum);
            f2lum.put(frame, lums);
          } else {
            lums.add(lum);
          }
        }
        r.close();
        return f2lum;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private Map<Frame, List<LuMatcher>> matchers =
      LuMatcher.getLuMatchersFromLuIndex();

  public static final Logger LOG = Logger.getLogger(TargetIndex.class);
  public Map<Span, Set<Frame>> findFrames(Sentence s, boolean requireInParens) {
    Map<Span, Set<Frame>> byTarget = new HashMap<>();
    for (Map.Entry<Frame, List<LuMatcher>> x : matchers.entrySet()) {
      Frame f = x.getKey();
      for (LuMatcher m : x.getValue()) {
        for (int i = 0; i < s.size(); i++) {
          Span t = m.matches(i, s, requireInParens);
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
