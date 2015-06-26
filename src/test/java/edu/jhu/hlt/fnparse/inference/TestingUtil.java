package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public class TestingUtil {

  public static void silenceLogs() {
    Logger.getLogger(CrfObjective.class).setLevel(Level.INFO);
  }

  public static List<FNParse> filterOutExamplesThatCantBeFit(List<FNParse> all) {
    List<FNParse> ret = new ArrayList<>();
    examples:
      for(FNParse p : all) {
        if(p.getFrameInstances().size() == 0)
          continue;
        if (p.getSentence().size() >= 50)
          continue;
        if (Arrays.asList("FNFUTXT1274829", "FNFUTXT1274797")
            .contains(p.getSentence().getId())) {
          // These contain an instance of "there be".v for Existence,
          // which we filter out (multi-word target)
          continue;
        }
        if ("FNFUTXT1274795".equals(p.getSentence().getId())) {
          // This has some strange issue with Quantity [amount] which I
          // should debug later, but doesn't seem to be a serious issue.
          continue;
        }
        for (FrameInstance fi : p.getFrameInstances()) {

          // TODO handle length > 1 targets!
          if (fi.getTarget().width() > 1)
            continue examples;

          // This frame is very poorly annotated. In principle it should be deterministically
          // read off from a CD pos-tag, but a small proportion of numbers are tagged with
          // the Cardinal_numbers frame.
          if ("Cardinal_numbers".equals(fi.getFrame().getName()))
            continue examples;
        }
        ret.add(p);
      }
    return ret;
  }
}
