package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class Describe {

  public static String span(Span s, Sentence sent) {
    StringBuilder sb = new StringBuilder();
    for(int i=s.start; i<s.end; i++) {
      if(i > s.start) sb.append(" ");
      sb.append(sent.getWord(i));
    }
    return sb.toString();
  }

  public static String sentenceWithDeps(Sentence sent) {
    return sentenceWithDeps(sent, false);
  }
  public static String sentenceWithDeps(Sentence sent, boolean basicDeps) {
    return spanWithDeps(Span.getSpan(0, sent.size()), sent, basicDeps);
  }

  public static String spanWithPos(Span s, Sentence sent) {
    return spanWithPos(s, sent, 0);
  }
  public static String spanWithPos(Span s, Sentence sent, int context) {
    StringBuilder sb = new StringBuilder();
    int start = Math.max(0, s.start - context);
    int end = Math.min(sent.size(), s.end + context);
    for (int i = start; i < end; i++) {
      if (i > start)
        sb.append(' ');
      if (i == s.start)
        sb.append('[');
      sb.append(sent.getWord(i));
      sb.append('/');
      sb.append(sent.getPos(i));
      if (i == s.end - 1)
        sb.append(']');
    }
    return sb.toString();
  }

  public static String spanWithDeps(Span s, Sentence sent) {
    return spanWithDeps(s, sent, false);
  }
  public static String spanWithDeps(Span s, Sentence sent, boolean basicDeps) {
    DependencyParse deps =
        basicDeps ? sent.getBasicDeps() : sent.getCollapsedDeps();
    if (deps == null)
      throw new IllegalStateException("deps not populated or wrong deps");
    StringBuilder sb = new StringBuilder();
    for (int i = s.start; i < s.end; i++) {
      int head = deps.getHead(i);
      String label = deps.getLabel(i);
      boolean root = head < 0 || head >= sent.size();
      sb.append(String.format("% 3d %-20s %-20s %-20s %-20s\n",
          i,
          sent.getWord(i),
          sent.getPos(i),
          label,
          root ? "ROOT" : sent.getWord(head)));
    }
    return sb.toString();
  }

  public static String sentence(Sentence s) {
    StringBuilder sb = new StringBuilder();
    for(int i=0; i<s.size(); i++) {
      if(i > 0) sb.append(" ");
      sb.append(s.getWord(i));
    }
    return sb.toString();
  }

  public static String frameInstance(FrameInstance fi) {
    StringBuilder sb = new StringBuilder();
    Sentence s = fi.getSentence();
    sb.append(String.format("FrameInstance of %s triggered by %s @ %d-%d:",
        fi.getFrame().getName(),
        Arrays.toString(s.getWordFor(fi.getTarget())),
        fi.getTarget().start,
        fi.getTarget().end));
    for (int k = 0; k < fi.numArguments(); k++) {
      Span extent = fi.getArgument(k);
      if (extent == Span.nullSpan)
        continue;
      sb.append(String.format(" %s[%d]=%s \"%s\"",
          fi.getFrame().getRole(k),
          k,
          extent.shortString(),
          span(fi.getArgument(k), s)));
      for (Span cont : fi.getContinuationRoleSpans(k))
        sb.append(" CONT=\"" + span(cont, s) + "\"");
      for (Span ref : fi.getReferenceRoleSpans(k))
        sb.append(" REF=\"" + span(ref, s) + "\"");
    }
    return sb.toString();
  }

  public static String frameInstaceJustArgsTerse(FrameInstance fi) {
    StringBuilder sb = new StringBuilder();
//    sb.append('[');
    int K = fi.getFrame().numRoles();
    boolean first = true;
    for (int k = 0; k < K; k++) {
      Span s = fi.getArgument(k);
      if (s != Span.nullSpan) {
        String sep = first ? "" : " ";
        sb.append(String.format("%s%d@%s", sep, k, s.shortString()));
        for (Span cont : fi.getContinuationRoleSpans(k))
          sb.append(" C-" + k + "@" + cont.shortString());
        for (Span ref : fi.getReferenceRoleSpans(k))
          sb.append(" R-" + k + "@" + ref.shortString());
        first = false;
      }
    }
    if (first)
      sb.append("NO_ARGS");
//    sb.append(']');
    return sb.toString();
  }

  // Sort by frame and position in sentence
  public static final Comparator<FrameInstance> fiComparator = new Comparator<FrameInstance>() {
    @Override
    public int compare(FrameInstance arg0, FrameInstance arg1) {
      int f = arg0.getFrame().getId() - arg1.getFrame().getId();
      if (f != 0) return f;
      int k = 1000;	// should be longer than a sentence
      return (k * arg0.getTarget().end + arg0.getTarget().start)
          - (k * arg1.getTarget().end + arg1.getTarget().start);
    }
  };

  public static String fnParse(FNParse p) {
    StringBuilder sb = new StringBuilder("FNParse");
    if (p.getId() != null && p.getId().length() > 0)
      sb.append(" " + p.getId());
    sb.append(": ");
    sb.append(sentence(p.getSentence()) + "\n");
    List<FrameInstance> fis = new ArrayList<>();
    fis.addAll(p.getFrameInstances());
    Collections.sort(fis, fiComparator);
    for(FrameInstance fi : fis)
      sb.append(frameInstance(fi) + "\n");
    return sb.toString();
  }

  public static String fnTagging(FNTagging p) {
    StringBuilder sb = new StringBuilder("FNTagging ");
    if (p.getId() != null && p.getId().length() > 0)
      sb.append(" " + p.getId());
    sb.append(": ");
    sb.append(sentence(p.getSentence()) + "\n");
    List<FrameInstance> fis = new ArrayList<>();
    fis.addAll(p.getFrameInstances());
    Collections.sort(fis, fiComparator);
    for(FrameInstance fi : fis)
      sb.append(frameInstance(fi) + "\n");
    return sb.toString();
  }

  public static String memoryUsage() {
    Runtime r = Runtime.getRuntime();
    return String.format("MemoryUsage used=%.1fG free=%.1fG limit=%.1fG",
        r.totalMemory() / (1024 * 1024 * 1024d),
        r.freeMemory() / (1024 * 1024 * 1024d),
        r.maxMemory() / (1024 * 1024 * 1024d));
  }
}
