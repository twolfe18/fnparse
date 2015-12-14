package edu.jhu.hlt.fnparse.data;

import java.io.File;

import edu.jhu.hlt.tutils.ExperimentProperties;

public class UsefulConstants {

  private static ExperimentProperties conf = ExperimentProperties.getInstance();

  public static File getDataPath() {
    return conf.getExistingFile("data.framenet.root", new File("toydata"));
  }

  public static File getFrameIndexPath() {
    return conf.getExistingFile("fn15.frameIndex", new File(getDataPath(), "fn15-frameindex"));
  }
  public static File getFrameIndexLUPath() {
    return conf.getExistingFile("fn15.frameIndexLU", new File(getDataPath(), "fn15-frameindexLU"));
  }

  public static File getTestFN15FullTextFramesPath() {
    return conf.getExistingFile("fn15.fulltext.test.frames", new File(getDataPath(), "fn15-fulltext.frames.test"));
  }
  public static File getTestFN15FullTextConllPath() {
    return conf.getExistingFile("fn15.fulltext.test.conll", new File(getDataPath(), "lemmatized_fn15-fulltext.conll.test"));
  }

  public static File getTestDipanjanFramesPath() {
    return conf.getExistingFile("fn15.fulltext.testDipanjan.frames", new File(getDataPath(), "fn15-fulltext.frames.test.dipanjan"));
  }
  public static File getTestDipanjanConllPath() {
    return conf.getExistingFile("fn15.fulltext.testDipanjan.conll", new File(getDataPath(), "fn15-fulltext.conll.test.dipanjan"));
  }

  public static File getTrainFN15FullTextFramesPath() {
    return conf.getExistingFile("fn15.fulltext.train.frames", new File(getDataPath(), "fn15-fulltext.frames.train"));
  }
  public static File getTrainFN15FullTextConllPath() {
    return conf.getExistingFile("fn15.fulltext.train.conll", new File(getDataPath(), "lemmatized_fn15-fulltext.conll.train"));
  }

  public static File getTrainDipanjanFramesPath() {
    return conf.getExistingFile("fn15.fulltext.trainDipanjan.frames", new File(getDataPath(), "fn15-fulltext.frames.train.dipanjan"));
  }
  public static File getTrainDipanjanConllPath() {
    return conf.getExistingFile("fn15.fulltext.trainDipanjan.conll", new File(getDataPath(), "fn15-fulltext.conll.train.dipanjan"));
  }

  // These are generated by toydata/make-debug-data.sh
  public static File getDebugFramesPath() {
    return conf.getExistingFile("fn15.fulltext.trainDipanjanDebug.frames", new File(getDataPath(), "fn15-fulltext.frames.train.dipanjan.small"));
  }
  public static File getDebugConllPath() {
    return conf.getExistingFile("fn15.fulltext.trainDipanjanDebug.conll", new File(getDataPath(), "fn15-fulltext.conll.train.dipanjan.small"));
  }

  public static File getFN15LexicographicFramesPath() {
    return conf.getExistingFile("fn15.lex.frames", new File(getDataPath(), "fn15-lex.frames"));
  }
  public static File getFN15LexicographicConllPath() {
    return conf.getExistingFile("fn15.lex.conll", new File(getDataPath(), "lemmatized_fn15-lex.conll"));
  }

  //public static final File SemLinkFramesPath = new File(getDataPath(), "semlink-fulltext.frames");
  //public static final File SemLinkConllPath = new File(getDataPath(), "semlink-fulltext.conll");

  //public static final File semLinkFrameInstanceFile = new File(getDataPath(), "semlink.1.2.2c");
}

