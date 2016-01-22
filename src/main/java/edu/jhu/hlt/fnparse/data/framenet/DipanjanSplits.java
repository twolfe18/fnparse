package edu.jhu.hlt.fnparse.data.framenet;

import java.io.BufferedReader;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

public class DipanjanSplits {

  // E.g. toydata/development-split.dipanjan-train.txt
  private File splitFile;
  private Set<String> train, dev, test;

  public DipanjanSplits(File splitFile) {
    Log.info("[main] reading Dipanjan's splits from splitFile=" + splitFile.getPath());
    this.splitFile = splitFile;
    this.train = new HashSet<>();
    this.dev = new HashSet<>();
    this.test = new HashSet<>();
    try (BufferedReader r = FileUtil.getReader(splitFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\t");
        if (toks.length != 2)
          throw new RuntimeException("must be 2 column TSV, line=\"" + line + "\"");
        boolean a;
        if ("test".equalsIgnoreCase(toks[0]))
          a = test.add(toks[1]);
        else if ("tune".equalsIgnoreCase(toks[0]))
          a = dev.add(toks[1]);
        else if ("train".equalsIgnoreCase(toks[0]))
          a = train.add(toks[1]);
        else
          a = false;
        if (!a) throw new RuntimeException("line=" + line);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    if (overlap(train, test) || overlap(train, dev) || overlap(dev, test))
      throw new RuntimeException();
  }

  public static boolean overlap(Set<?> s1, Set<?> s2) {
    for (Object a : s1)
      if (s2.contains(a))
        return true;
    return false;
  }

  public DipanjanSplits(ExperimentProperties config) {
    this(ExperimentProperties.getInstance().getExistingFile("framenet.dipanjan.splits"));
  }

  public File getSplitFile() {
    return splitFile;
  }

  public boolean isTrain(FNParse y) {
    return isTrain(y.getId());
  }
  public boolean isTrain(String parseId) {
    return train.contains(parseId);
  }

  public boolean isDev(FNParse y) {
    return isDev(y.getId());
  }
  public boolean isDev(String parseId) {
    return dev.contains(parseId);
  }

  public boolean isTest(FNParse y) {
    return isTest(y.getId());
  }
  public boolean isTest(String parseId) {
    return test.contains(parseId);
  }
}
