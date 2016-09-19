package edu.jhu.hlt.ikbp;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.TIOStreamTransport;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.ingesters.base.IngestException;
import edu.jhu.hlt.concrete.ingesters.gigaword.GigawordStreamIngester;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * This class loops over all of annotated gigaword to pull out the
 * concrete-stanford annnotated communications.
 * 
 * Unused since I couldn't compile this on the COE (maven server down)
 * and in the mean time Chandler did this for me in python.
 * 
 * @deprecated This is not used, see data/parma/roth-frank/manual-alignments/Makefile
 * 
 * @author travis
 */
public class RfToConcreteBruteForce {

  private Set<String> relevantDocIds;

  public RfToConcreteBruteForce(File relevantDocIdsFile) throws IOException {
    relevantDocIds = new HashSet<>();
    try (BufferedReader r = FileUtil.getReader(relevantDocIdsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        Log.info("relevant: " + line.trim());
        relevantDocIds.add(line.trim());
      }
    }
    Log.info("there are " + relevantDocIds.size() + " relevant docs");
  }
  
  // e.g. /export/common/data/processed/concrete/concretely-annotated/gigaword/stanford
  public void scan(File annotatedGigawordDir, Consumer<Communication> visitor) throws IngestException {
    Log.info("scanning communications in " + annotatedGigawordDir.getPath());
    GigawordStreamIngester g = new GigawordStreamIngester(annotatedGigawordDir.toPath());
    g.stream()
      .filter(c -> relevantDocIds.contains(c.getId()))
      .forEach(visitor::accept);
  }

  public void scanAndWriteOneFilePerComm(File annotatedGigawordDir, File outputCommDir) throws IngestException {
    scan(annotatedGigawordDir, comm -> {
      File out = new File(outputCommDir, comm.getId() + ".concrete");
      Log.info("writing to " + out.getPath());
      try (BufferedOutputStream b = new BufferedOutputStream(new FileOutputStream(out))) {
        comm.write(new TCompactProtocol(new TIOStreamTransport(b)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File annotatedGigawordDir = config.getExistingDir("gigaword", new File("/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford"));
//    File parent = new File("/home/travis/code/fnparse/data/parma/roth-frank/manual-alignments");
    File parent = new File("/home/hltcoe/twolfe/fnparse-build/fnparse/data/parma/roth-frank/manual-alignments");
    File docIds = config.getExistingFile("docIds", new File(parent, "doc_ids.txt"));
    File outputCommunicationDir = config.getOrMakeDir("output", new File(parent, "stanford-annoated-communications"));
    
    RfToConcreteBruteForce r = new RfToConcreteBruteForce(docIds);
    r.scanAndWriteOneFilePerComm(annotatedGigawordDir, outputCommunicationDir);
  }
}
