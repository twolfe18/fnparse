package edu.jhu.hlt.ikbp.tac;

public class FeatureHashCLI {
  
  public static void main(String[] args) {
    for (String a : args) {
      System.out.println(Integer.toUnsignedString(ReversableHashWriter.onewayHash(a)) + "\t" + a);
    }
  }

}
