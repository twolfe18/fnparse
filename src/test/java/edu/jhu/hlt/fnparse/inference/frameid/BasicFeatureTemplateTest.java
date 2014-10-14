package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.util.Timer;

public class BasicFeatureTemplateTest {
  static Logger LOG = Logger.getLogger(BasicFeatureTemplateTest.class);

  @Test
  public void doesntCrash() {
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    TemplateContext ctx = new TemplateContext();
    for (String tName : BasicFeatureTemplates.getBasicTemplateNames()) {
      //LOG.info("testing " + tName);
      Template template = BasicFeatureTemplates.getBasicTemplate(tName);
      int numCalls = 0;
      int numFired = 0;
      int numValues = 0;
      Set<String> uniq = new HashSet<>();
      Timer timer = new Timer(null, -1, true);
      for (FNParse p : parses) {
        ctx.setSentence(p.getSentence());
        for (FrameInstance fi : p.getFrameInstances()) {
          ctx.setTarget(fi.getTarget());
          ctx.setFrame(fi.getFrame());
          timer.start();
          Iterable<String> t = template.extract(ctx);
          timer.stop();
          numCalls++;
          if (t != null) {
            numFired++;
            for (String s : t) {
              uniq.add(s);
              numValues++;
            }
          }
        }
      }
      String rate = String.format("%.1f calls/ms", timer.countsPerMSec());
      LOG.info(String.format(
          "%s fired %d of %d times (%.1f%%) with %.1f values/fire, a rate of %s, and a cardinality of %d",
          tName,
          numFired,
          numCalls,
          (100d * numFired) / numCalls,
          numValues / ((double) numFired),
          rate,
          uniq.size()));
    }
  }

}
