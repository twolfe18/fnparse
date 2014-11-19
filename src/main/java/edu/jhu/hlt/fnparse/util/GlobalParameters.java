package edu.jhu.hlt.fnparse.util;

import java.util.Random;

import edu.jhu.util.Alphabet;

/**
 * These are really the only things that need to be global.
 * 
 * @author travis
 */
public class GlobalParameters {
  private Alphabet<String> featureNames = new Alphabet<>();
  private Random rand = new Random(9001);

  public Random getRandom() {
    return rand;
  }

  public Alphabet<String> getFeatureNames() {
    return featureNames;
  }
}
