package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;

public class FNDiff {

  public static String diffArgs(FNParse a, FNParse b, boolean printSame) {
    if (!a.getSentence().getId().equals(b.getSentence().getId()))
      throw new IllegalArgumentException();
    Set<FrameArgInstance> aroles = new HashSet<>();
    SentenceEval.fillPredictions(a.getFrameInstances(), null, null, aroles);
    Set<FrameArgInstance> broles = new HashSet<>();
    SentenceEval.fillPredictions(b.getFrameInstances(), null, null, broles);
    Set<FrameArgInstance> allroles = new HashSet<>();
    allroles.addAll(aroles);
    allroles.addAll(broles);

    // In a but not b
    Set<FrameArgInstance> aNotB = new HashSet<>();
    aNotB.addAll(aroles);
    aNotB.removeAll(broles);

    // In b but not a
    Set<FrameArgInstance> bNotA = new HashSet<>();
    bNotA.addAll(broles);
    bNotA.removeAll(aroles);

    List<FrameArgInstance> allrolesSorted = new ArrayList<>();
    allrolesSorted.addAll(allroles);
    Collections.sort(allrolesSorted);	// Lexicographically
    StringBuilder sb = new StringBuilder();
    for (FrameArgInstance s : allrolesSorted) {
      if (aNotB.contains(s)) {
        sb.append("- ");
      } else if (bNotA.contains(s)) {
        sb.append("+ ");
      } else {
        if (printSame) sb.append(" ");
        else continue;
      }
      sb.append(s.describeAsFrameInstance(a.getSentence()));
      sb.append("\n");
    }
    return sb.toString();
  }

  public static String diffFrames(
      FNTagging a, FNTagging b, boolean printSame) {
    Set<FrameArgInstance> aroles = new HashSet<>();
    SentenceEval.fillPredictions(a.getFrameInstances(), aroles, null, null);
    Set<FrameArgInstance> broles = new HashSet<>();
    SentenceEval.fillPredictions(b.getFrameInstances(), broles, null, null);
    Set<FrameArgInstance> allroles = new HashSet<>();
    allroles.addAll(aroles);
    allroles.addAll(broles);
    Sentence sent = a.getSentence();
    StringBuilder sb = new StringBuilder();
    for (FrameArgInstance fi : allroles) {
      if (broles.contains(fi) && !aroles.contains(fi)) {
        sb.append("+ ");
      } else if (aroles.contains(fi) && !broles.contains(fi)) {
        sb.append("- ");
      } else {
        if (printSame)
          sb.append("  ");
        else
          continue;
      }
      sb.append(fi.describeAsFrameInstance(sent));
      sb.append("\n");
    }
    return sb.toString();
  }
}
