package edu.jhu.hlt.entsum;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;

public class Summary implements Serializable {
  private static final long serialVersionUID = 5796571498596058534L;

  public static class Concept implements Serializable {
    private static final long serialVersionUID = -5686978313618341650L;
    public String name;
    public int sentence;
    public Span tokens;
    public double utility;
    public double mentionCost;

    public Concept(String name, int sentence, Span tokens, double utility, double mentionCost) {
      this.name = name;
      this.sentence = sentence;
      this.tokens = tokens;
      this.utility = utility;
      this.mentionCost = mentionCost;
    }
    
    public Concept changeSentence(int newSentence) {
      return new Concept(name, newSentence, tokens, utility, mentionCost);
    }
    
    public double bangForBuck() {
      if (mentionCost == 0)
        return Double.POSITIVE_INFINITY;
      return utility / mentionCost;
    }
    
    public static final Comparator<Concept> BY_BANG_FOR_BUCK_DESC = new Comparator<Concept>() {
      @Override
      public int compare(Concept o1, Concept o2) {
        double r1 = o1.bangForBuck();
        double r2 = o2.bangForBuck();
        if (r1 > r2)
          return -1;
        if (r2 > r1)
          return +1;
        return 0;
      }
    };
  }

  public String subject;            // e.g. a mid
  public List<EffSent> sentences;
  public List<Concept> concepts;
  
  public Summary(String subject) {
    this.subject = subject;
    this.sentences = new ArrayList<>();
    this.concepts = new ArrayList<>();
  }

  public Summary(String mid, List<EffSent> sentences) {
    this.subject = mid;
    this.sentences = sentences;
    this.concepts = new ArrayList<>();
  }
  
  private static class Sent implements Comparable<Sent> {
    double score;
    int oldIdx;
    EffSent sent;
    List<Concept> concepts;
    @Override
    public int compareTo(Sent o) {
      if (score > o.score)
        return -1;
      if (score < o.score)
        return +1;
      return 0;
    }
  }
  private void add(Sent si, int[] old2newSentIdx) {
    sentences.add(si.sent);
    for (Concept ci : si.concepts)
      concepts.add(ci.changeSentence(old2newSentIdx[ci.sentence]));
  }
  public Summary orderSentencesByUtility() {
    List<Sent> s = new ArrayList<>();
    for (int i = 0; i < sentences.size(); i++) {
      Sent si = new Sent();
      si.oldIdx = i;
      si.sent = sentences.get(i);
      si.concepts = conceptsIn(i);
      for (Concept c : si.concepts)
        si.score += c.utility - c.mentionCost;
      s.add(si);
    }
    Collections.sort(s);

    // Change the sentence indices
    int[] old2new = new int[s.size()];
    for (int i = 0; i < s.size(); i++) {
      Sent si = s.get(i);
      old2new[si.oldIdx] = i;
    }

    Summary sn = new Summary(subject);
    for (Sent si : s)
      sn.add(si, old2new);

    return sn;
  }
  
  public void addConcept(int sentence, Span tokens, String name, double utility, double mentionCost) {
    this.concepts.add(new Concept(name, sentence, tokens, utility, mentionCost));
  }
  
  public List<Concept> conceptsIn(int sentence) {
    List<Concept> out = new ArrayList<>();
    for (Concept c : concepts) {
      assert c.sentence >= 0 && c.sentence < sentences.size();
      if (c.sentence == sentence)
        out.add(c);
    }
    return out;
  }
  
  public void show(MultiAlphabet a) {
    for (int i = 0; i < sentences.size(); i++) {
      EffSent s = sentences.get(i);
      List<Concept> ci = conceptsIn(i);
      Collections.sort(ci, Concept.BY_BANG_FOR_BUCK_DESC);
      System.out.println("sentence=" + i + " nWord=" + s.parse().length + " nConcept=" + ci.size());
      s.showConllStyle(a);
      System.out.print("concepts:");
      for (Concept c : ci) {
        String loc = c.tokens == Span.nullSpan ? "?" : c.tokens.shortString();
        System.out.printf("  [%s u=%.2f c=%.2f @%s]", c.name, c.utility, c.mentionCost, loc);
      }
      System.out.println("\n");
    }
  }
}