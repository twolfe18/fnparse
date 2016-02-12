package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.google.common.collect.Iterators;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;

public class DumpSentenceIds {

  public static Iterator<FNParse> getData(ExperimentProperties config) {
    String ds = config.getString("data");
    String part = config.getString("part");
    Iterator<FNParse> data = Collections.emptyIterator();
    if (ds.equalsIgnoreCase("propbank") || ds.equalsIgnoreCase("both")) {
      ParsePropbankData.Redis propbankAutoParses = null;
      PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
      if (part.equalsIgnoreCase("test")) {
        data = Iterators.concat(data, pbr.getTestData().iterator());
      } else if (part.equalsIgnoreCase("dev")) {
        data = Iterators.concat(data, pbr.getDevData().iterator());
      } else if (part.equalsIgnoreCase("train")) {
        data = Iterators.concat(data, pbr.getTrainData().iterator());
      }
    }
    if (ds.equalsIgnoreCase("framenet") || ds.equalsIgnoreCase("both")) {
      if (part.equalsIgnoreCase("test")) {
        data = Iterators.concat(data, FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences());
      } else if (part.equalsIgnoreCase("train")) {
        data = Iterators.concat(data, FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
      }
    }
    return data;
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
