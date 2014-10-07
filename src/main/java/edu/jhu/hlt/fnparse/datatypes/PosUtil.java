package edu.jhu.hlt.fnparse.datatypes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PosUtil {

  public static boolean pedantic = false;

  public static edu.mit.jwi.item.POS ptb2wordNet(String ptbPosTag) {
    if(ptbPosTag.startsWith("J"))
      return edu.mit.jwi.item.POS.ADJECTIVE;
    if(ptbPosTag.startsWith("R"))
      return edu.mit.jwi.item.POS.ADVERB;
    if(ptbPosTag.startsWith("N"))
      return edu.mit.jwi.item.POS.NOUN;
    if(ptbPosTag.startsWith("V"))
      return edu.mit.jwi.item.POS.VERB;
    if(pedantic)
      throw new IllegalArgumentException("is this a ptb tag? " + ptbPosTag);
    return null;
  }

  public static edu.mit.jwi.item.POS fn2wordNet(String fnPosTag) {
    if(fnPosTag.equals("A"))
      return edu.mit.jwi.item.POS.ADJECTIVE;
    if(fnPosTag.equals("ADV"))
      return edu.mit.jwi.item.POS.ADVERB;
    if(fnPosTag.equals("N"))
      return edu.mit.jwi.item.POS.NOUN;
    if(fnPosTag.equals("V"))
      return edu.mit.jwi.item.POS.VERB;
    if(pedantic)
      throw new IllegalArgumentException("is this a fn tag? " + fnPosTag);
    return null;
  }

  private static Map<String, String> frameNetPosToPennPrefixes;
  private static Map<String, List<String>> frameNetPosToAllPennTags;
  private static Map<String, String> penn2frameNetPos;
  static {
    frameNetPosToPennPrefixes = new HashMap<String, String>();
    frameNetPosToPennPrefixes.put("A", "J");	// A=adjective
    frameNetPosToPennPrefixes.put("ADV", "R");
    frameNetPosToPennPrefixes.put("ART", "D");	// D=determiner
    frameNetPosToPennPrefixes.put("C", "CC");
    frameNetPosToPennPrefixes.put("INTJ", "UH");
    frameNetPosToPennPrefixes.put("N", "NN");
    frameNetPosToPennPrefixes.put("NUM", "CD");
    frameNetPosToPennPrefixes.put("PREP", "IN");
    frameNetPosToPennPrefixes.put("SCON", "IN");
    frameNetPosToPennPrefixes.put("V", "V");

    frameNetPosToAllPennTags = new HashMap<String, List<String>>();
    frameNetPosToAllPennTags.put("A", Arrays.asList("JJ", "JJR", "JJS"));
    frameNetPosToAllPennTags.put("ADV", Arrays.asList("RB", "RBR", "RBS"));
    frameNetPosToAllPennTags.put("ART", Arrays.asList("DT", "PDT"));
    frameNetPosToAllPennTags.put("C", Arrays.asList("CC"));
    frameNetPosToAllPennTags.put("INTJ", Arrays.asList("UH"));
    frameNetPosToAllPennTags.put("N", Arrays.asList("NN", "NNP", "NNPS", "NNS"));
    frameNetPosToAllPennTags.put("NUM", Arrays.asList("CD"));
    frameNetPosToAllPennTags.put("PREP", Arrays.asList("IN"));
    frameNetPosToAllPennTags.put("SCON", Arrays.asList("IN"));
    frameNetPosToAllPennTags.put("V", Arrays.asList("VB", "VBD", "VBG", "VBN", "VBP", "VBZ"));

    penn2frameNetPos = new HashMap<String, String>();
    for(Map.Entry<String, List<String>> x : getFrameNetPosToAllPennTags().entrySet()) {
      String fnTag = x.getKey();
      for(String pennTag : x.getValue())
        penn2frameNetPos.put(pennTag, fnTag);
    }

    // there is only one non-bijective case:
    penn2frameNetPos.put("IN", "PREP");
  }

  public static Map<String, String> getFrameNetPosToPennPrefixesMap() {
    return frameNetPosToPennPrefixes;
  }

  public static Map<String, List<String>> getFrameNetPosToAllPennTags() {
    return frameNetPosToAllPennTags;
  }

  public static Map<String, String> getPennToFrameNetTags() {
    return penn2frameNetPos;
  }
}
