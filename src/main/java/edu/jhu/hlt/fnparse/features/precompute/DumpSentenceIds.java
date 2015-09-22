package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;

public class DumpSentenceIds {

  public static Iterator<FNParse> getData(ExperimentProperties config) {
    String ds = config.getString("data");
    String part = config.getString("part");
    if (ds.equalsIgnoreCase("propbank")) {
      ParsePropbankData.Redis propbankAutoParses = null;
      PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
      if (part.equalsIgnoreCase("test")) {
        return pbr.getTestData().iterator();
      } else if (part.equalsIgnoreCase("dev")) {
        return pbr.getDevData().iterator();
      } else if (part.equalsIgnoreCase("train")) {
        return pbr.getTrainData().iterator();
      }
    } else if (ds.equalsIgnoreCase("framenet")) {
      if (part.equalsIgnoreCase("test")) {
        return FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences();
      } else if (part.equalsIgnoreCase("train")) {
        return FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
      }
    }
    throw new RuntimeException("unknow: ds=" + ds + " part=" + part);
  }

  public static Set<String> getSentenceIds(Iterator<FNParse> itr) {
    Set<String> ids = new HashSet<>();
    while (itr.hasNext()) {
      FNParse y = itr.next();
      ids.add(y.getSentence().getId());
    }
    return ids;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    Iterator<FNParse> itr = getData(config);
    Set<String> ids = getSentenceIds(itr);
    try (BufferedWriter w = FileUtil.getWriter(config.getFile("output"))) {
      for (String id : ids) {
        w.write(id);
        w.newLine();
      }
    }
  }
}
