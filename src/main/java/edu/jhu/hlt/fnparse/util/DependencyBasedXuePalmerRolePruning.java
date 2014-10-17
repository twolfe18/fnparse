package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning;

public class DependencyBasedXuePalmerRolePruning {
  public static final Logger LOG =
      Logger.getLogger(DependencyBasedXuePalmerRolePruning.class);
  public static boolean DEBUG = true;

  public static void xuePalmerHelper(
      int i,
      DependencyParse parse,
      Collection<Span> spans) {
    spans.add(parse.getSpan(i));
    for (int sib : parse.getSiblings(i)) {
      spans.add(parse.getSpan(sib));
      if ("prep".equals(parse.getLabel(sib))) {
        for (int niece : parse.getChildren(sib))
          spans.add(parse.getSpan(niece));
      }
    }
    if (parse.getHead(i) >= 0)
      xuePalmerHelper(parse.getHead(i), parse, spans);
  }

  /**
   * Expects that input.sentence has had its basic dependencies populated.
   */
  public static Map<FrameInstance, List<Span>> getMask(
      FNTagging input,
      DeterministicRolePruning.Mode mode) {
    assert mode == DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN
        || mode == DeterministicRolePruning.Mode.XUE_PALMER_DEP;
    Map<FrameInstance, List<Span>> possibleSpans = new HashMap<>();
    for (FrameInstance fi : input.getFrameInstances()) {
      int i = fi.getTarget().end - 1;
      if (fi.getTarget().width() > 1) {
        LOG.warn("[mode=" + mode + "] width="
            + fi.getTarget().width()
            + " target="
            + Describe.span(fi.getTarget(), fi.getSentence()));
      }
      Set<Span> spanSet = new HashSet<>();
      DependencyParse deps = input.getSentence().getBasicDeps();
      xuePalmerHelper(i, deps, spanSet);
      if (mode == DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN) {
        // 1) Add the target itself
        spanSet.add(fi.getTarget());

        // These rules only make sense if the predicate is not the root verb
        int p = deps.getHead(i);
        if (p >= 0) {
          int s, e;
          // 2)
          s = fi.getTarget().end;
          e = deps.getProjRight(p);
          if (s <= e) {
            spanSet.add(Span.getSpan(s, e + 1));
          } else {
            // If the predicate is the rightmost child, this won't work
            // e.g. target=<Span 20-21>, parent=
            //    18 of             IN       prep       delivery
            //    19 bioactive      JJ       amod       agents
            //    20 agents         NNS      pobj       of
            //LOG.debug("2) target=" + fi.getTarget());
            //LOG.debug("2) parent=\n" + Describe.spanWithDeps(
            //    deps.getSpan(p), fi.getSentence(), true));
          }
          // 3)
          s = deps.getProjLeft(p);
          e = fi.getTarget().start;
          if (s <= e) {
            spanSet.add(Span.getSpan(s, e + 1));
          } else {
            LOG.warn("a predicate was the rightmost child of its parent?");
            LOG.warn("predicates are usually heads, and heads are usually to "
                + "the right in English...");
            LOG.debug("3) target=" + fi.getTarget());
            LOG.debug("3) parent=\n" + Describe.spanWithDeps(
                deps.getSpan(p), fi.getSentence(), true));
          }
        }
      }
      // Convert set to list
      List<Span> spans = new ArrayList<>();
      spans.add(Span.nullSpan);
      spans.addAll(spanSet);
      // Store the spans using only frame/target information
      FrameInstance key = FrameInstance.frameMention(
          fi.getFrame(), fi.getTarget(), fi.getSentence());
      possibleSpans.put(key, spans);
    }
    return possibleSpans;
  }

  /**
   * Returns a map where the keys are spans and the values are the index of
   * the headword of that span.
   */
  public static Map<Span, Integer> getAllSpansFromDeps(
      Sentence s,
      boolean includeHermannStyleExtensions) {
    if (DEBUG)
      LOG.debug("[getAllSpansFromDeps] for sentence " + s.getId());
    Map<Span, Integer> spans = new HashMap<>();
    boolean[] seen = new boolean[s.size()];
    for (int i = 0; i < s.size(); i++) {
      if (s.governor(i) < 0 || s.governor(i) >= s.size())
        helper(s, i, spans, seen, false);
    }
    return spans;
  }

  private static void helper(
      Sentence s,
      int i,
      Map<Span, Integer> addTo,
      boolean[] seen,
      boolean includeHermannStyleExtensions) {
    if (s.childrenOf(i).length == 0)
      return;
    int l = i;
    int r = i;
    for (int c : s.childrenOf(i)) {
      if (c < l) l = c;
      if (c > r) r = c;
    }
    if (DEBUG)
      LOG.debug("[helper] span around " + i + " is [" + l + "," + (r+1) + ")");
    Span span = Span.getSpan(l, r + 1);
    Integer iOld = addTo.put(span, i);
    if (includeHermannStyleExtensions) {
      if (l < i) {
        Span sp = Span.getSpan(l, i);
        iOld = addTo.put(sp, i);
        if (iOld != null)
          addTo.put(sp, iOld);
      }
      if (i > r) {
        Span sp = Span.getSpan(i + 1, r + 1);
        iOld = addTo.put(sp, i);
        if (iOld != null)
          addTo.put(sp, iOld);
      }
    }
    for (int c : s.childrenOf(i)) {
      if (seen[c]) continue;
      seen[c] = true;
      if (DEBUG)
        LOG.debug("[helper] recursing on " + c);
      helper(s, c, addTo, seen, false);
    }
  }
}
