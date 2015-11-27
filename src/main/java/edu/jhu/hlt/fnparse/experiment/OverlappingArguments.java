package edu.jhu.hlt.fnparse.experiment;

import java.util.*;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.tutils.Span;

/**
 * Right now my decoder takes a max over headwords (j) for every role (k).
 * 
 * Can different roles (k) be filled by one headword/span (j) ?
 *  => YES (but rarely)
 * 
 * @author travis
 */
public class OverlappingArguments {

  public static boolean overlappingArgs(FrameInstance fi) {
    Sentence s = fi.getSentence();
    Frame f = fi.getFrame();
    boolean someOverlap = false;
    int n =fi.numArguments();
    for(int i=0; i<n-1; i++) {
      Span ai = fi.getArgument(i);
      if(ai == Span.nullSpan) continue;
      for(int j=i+1; j<n; j++) {
        Span aj = fi.getArgument(j);
        if(aj == Span.nullSpan) continue;
        if(ai.overlaps(aj)) {
          someOverlap = true;
          String aid = f.getRole(i) + "=" + Arrays.toString(s.getWordFor(ai));
          String ajd = f.getRole(j) + "=" + Arrays.toString(s.getWordFor(aj));
          System.out.printf("%s %s overlaps %s\n", f.getName(), aid, ajd);
        }
      }
    }
    if(someOverlap)
      System.out.println();
    return someOverlap;
  }

  public static void main(String[] args) {
    int overlapping = 0, total = 0;
    Iterator<FNTagging> iter = FileFrameInstanceProvider.fn15trainFIP
        .getParsedOrTaggedSentences();
    while(iter.hasNext()) {
      FNTagging t = iter.next();
      for(FrameInstance fi : t.getFrameInstances()) {
        if(overlappingArgs(fi))
          overlapping++;
        total++;
      }
    }
    System.out.printf(
        "%d of %d (%.1f%%) FrameInstances have overlapping arguments\n",
        overlapping, total, (100d*overlapping)/total);
  }
}
