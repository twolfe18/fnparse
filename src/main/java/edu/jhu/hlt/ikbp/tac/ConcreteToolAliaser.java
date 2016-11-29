package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;

public class ConcreteToolAliaser {
  
  public static class DParse {
    /**
     * Copy a parse with tool name sourceTool, making a new parse
     * with tool name destTool.
     */
    public static void copyIfNotPresent(Communication c, String sourceTool, String destTool, boolean allowFail) {
      for (Section section : c.getSectionList()) {
        for (Sentence sentence : section.getSentenceList()) {
          Tokenization tokenization = sentence.getTokenization();
          List<String> present = new ArrayList<>();
          DependencyParse dpDst = null;
          DependencyParse dpSrc = null;
          for (DependencyParse d : tokenization.getDependencyParseList()) {
            String t = d.getMetadata().getTool();
            present.add(t);
            if (destTool.equals(t)) {
              assert dpDst == null;
              dpDst = d;
            }
            if (sourceTool.equals(t)) {
              assert dpSrc == null;
              dpSrc = d;
            }
          }
          if (dpDst == null) {
            if (dpSrc == null) {
              if (!allowFail)
                throw new RuntimeException("no dparse named " + sourceTool + ", toolsPresent=" + present);
            } else {
              DependencyParse copy = new DependencyParse();
              copy.setUuid(new UUID(""));
              copy.setDependencyList(dpSrc.getDependencyList());
              copy.setMetadata(new AnnotationMetadata().setTool(destTool));
              tokenization.addToDependencyParseList(copy);
            }
          }
        }
      }
    }
  }

}
