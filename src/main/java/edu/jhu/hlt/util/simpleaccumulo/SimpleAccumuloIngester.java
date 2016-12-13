package edu.jhu.hlt.util.simpleaccumulo;

import java.io.File;
import java.util.List;

import org.apache.accumulo.core.client.security.tokens.PasswordToken;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.ikbp.tac.IndexCommunications;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.utilt.AutoCloseableIterator;

/**
 * Ingests {@link Communication}s from tgz archives.
 *
 * @author travis
 */
public class SimpleAccumuloIngester {
  
  public static AutoCloseableIterator<Communication> getCommunicationsToIngest(ExperimentProperties config) {
    List<File> tgzArchives = config.getFileGlob("communications");
    return new IndexCommunications.FileBasedCommIter(tgzArchives);
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    TimeMarker tm = new TimeMarker();
    int stored = 0;
    int storedPrev = 0;
    double interval = 5;
    Average rateAvgLocal = new Average.Exponential(0.9);
//    Average rateAvgGlobal = new Average.Uniform();
    SimpleAccumuloConfig saConf = SimpleAccumuloConfig.fromConfig(config);    
    Log.info("using " + saConf);
    int numThreads = config.getInt("numThreads", 1);
    try (SimpleAccumuloStore ingester = new SimpleAccumuloStore(saConf, numThreads);
        AutoCloseableIterator<Communication> comms = getCommunicationsToIngest(config)) {
      ingester.connect(
          config.getString("accumulo.user"),
          new PasswordToken(config.getString("accumulo.password")));  // TODO better security
      while (comms.hasNext()) {
        Communication c = comms.next();
        ingester.store(c);
        stored++;
        if (tm.enoughTimePassed(interval)) {
          double rate = (stored - storedPrev) / interval;
          rateAvgLocal.add(rate);
//          rateAvgGlobal.add(rate);
          double rateAvgGlobal = stored / tm.secondsSinceFirstMark();
          storedPrev = stored;
          Log.info(String.format(
              "stored=%d communications cur_row=%s\trateRecent=%.1f comm/sec rateAll=%.1f comm/sec",
              stored, c.getId(), rateAvgLocal.getAverage(), rateAvgGlobal));
        }
      }
    }
    Log.info("done, stored=" + stored);
  }
}
