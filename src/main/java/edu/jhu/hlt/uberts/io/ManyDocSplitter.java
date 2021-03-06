package edu.jhu.hlt.uberts.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * If you're looking for a way to filter RelDoc streams, you can do it here.
 *
 * @author travis
 */
public abstract class ManyDocSplitter implements AutoCloseable {

  /**
   * If you return null, then this doesn't belong to any partition.
   */
  abstract String partition(RelDoc d);

  abstract File getOutputForPartition(String p);


  /**
   * Takes a set of ids for test, and possibly dev, and splits a many doc fact/rel
   * file according to these ids.
   */
  public static class TestSet extends ManyDocSplitter {
    private File testIdsFile;
    private File trainOutput, testOutput;
    private Set<String> testDocIds;

    // Optional: dev set
//    private File devIdsFile;
    private File devOutput;
    private Set<String> devDocIds;

    public TestSet(File fileOfDocIds, File trainOutput, File testOutput) throws IOException {
      Log.info("reading ids from " + fileOfDocIds
          + " and writing train data to " + trainOutput
          + " and test data to " + testOutput);
      this.testIdsFile = fileOfDocIds;
      this.trainOutput = trainOutput;
      this.testOutput = testOutput;
      this.testDocIds = new HashSet<>();
      try (BufferedReader r = FileUtil.getReader(testIdsFile)) {
        for (String line = r.readLine(); line != null; line = r.readLine())
          testDocIds.add(line);
      }
      Log.info("read " + testDocIds.size() + " ids");
    }

    public void setDev(File devIdsFile, File devOutputFile) throws IOException {
      Log.info("reading dev ids from " + devIdsFile + " and writing dev output to " + devOutputFile);
//      this.devIdsFile = devIdsFile;
      this.devOutput = devOutputFile;
      this.devDocIds = new HashSet<>();
      try (BufferedReader r = FileUtil.getReader(devIdsFile)) {
        for (String line = r.readLine(); line != null; line = r.readLine())
          devDocIds.add(line);
      }
      Log.info("read " + devDocIds.size() + " ids");
    }

    @Override
    public String partition(RelDoc d) {
      if (testDocIds.contains(d.getId()))
        return "test";
      if (devDocIds != null && devDocIds.contains(d.getId()))
        return "dev";
      return "train";
    }

    @Override
    public File getOutputForPartition(String p) {
      if ("test".equals(p))
        return testOutput;
      if ("dev".equals(p)) {
        assert devOutput != null;
        return devOutput;
      }
      assert "train".equals(p);
      return trainOutput;
    }
  }


  /**
   * Splits a many doc input file into pieces of size N, where N is provided.
   */
  public static class ByBlockSize extends ManyDocSplitter {
    int written;
    int block;
    int blockSize;
    File outputDir;
    String fileNameFormat;
    /**
     * @param outputDir is where to put splits
     * @param fileName should be a format string for output files with a hole for a single integer.
     * @param blockSize
     */
    public ByBlockSize(File outputDir, String fileNameFormat, int blockSize) {
      this.outputDir = outputDir;
      this.fileNameFormat = fileNameFormat;
      this.written = 0;
      this.block = 0;
      this.blockSize = blockSize;
    }
    @Override
    String partition(RelDoc d) {
      written++;
      if (written >= blockSize) {
        block++;
        written = 0;
      }
      return String.format(fileNameFormat, block);
    }
    @Override
    File getOutputForPartition(String p) {
      return new File(outputDir, p);
    }
  }



  private Map<String, BufferedWriter> writers = new HashMap<>();
  private Counts<String> partitionCounts = new Counts<>();

  public void split(File f) throws IOException {
    Log.info("reading from " + f);
    boolean dedup = true;
    try (RelationFileIterator rfi = new RelationFileIterator(f, false);
        ManyDocRelationFileIterator itr = new ManyDocRelationFileIterator(rfi, dedup)) {
      split(itr);
    }
    Log.info("done, " + partitionCounts);
  }

  public void split(Iterator<RelDoc> itr) throws IOException {
    TimeMarker tm = new TimeMarker();
    while (itr.hasNext()) {
      if (tm.enoughTimePassed(15)) {
        Log.info("after " + tm.secondsSinceFirstMark() + " sec: " + partitionCounts);
      }

      RelDoc d = itr.next();
      String p = partition(d);
      if (p == null) {
        partitionCounts.increment("<skipped>");
        continue;
      }
      partitionCounts.increment(p);
      BufferedWriter w = writers.get(p);
      if (w == null) {
        w = FileUtil.getWriter(getOutputForPartition(p));
        writers.put(p, w);
      }
      w.write(d.def.toLine());
      w.newLine();
      assert d.facts.isEmpty() : "not supported yet";
      for (RelLine l : d.items) {
        w.write(l.toLine());
        w.newLine();
      }
      w.flush();
    }
  }

  @Override
  public void close() {
    for (BufferedWriter w : writers.values()) {
      try {
        w.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    String m = config.getString("mode");
    switch (m) {
    case "trainDevTest":
      try (TestSet ts = new TestSet(config.getExistingFile("testSetIds"),
          config.getFile("trainRelOutput"),
          config.getFile("testRelOutput"))) {
        if (config.containsKey("devSetIds")) {
          ts.setDev(config.getExistingFile("devSetIds"),
              config.getFile("devRelOutput"));
        }
        ts.split(config.getExistingFile("relInput"));
      }
      break;
    case "byBlockSize":
      int bs = config.getInt("blockSize");
      String ff = config.getString("fileNameFormat", "shard-%d.facts.gz");
      File output = config.getFile("output");
      try (ByBlockSize bbs = new ByBlockSize(output, ff, bs)) {
        bbs.split(config.getExistingFile("input"));
      }
      break;
    default:
      throw new RuntimeException("unknown mode: " + m);
    }
  }
}
