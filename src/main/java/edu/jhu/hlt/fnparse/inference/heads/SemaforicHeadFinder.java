package edu.jhu.hlt.fnparse.inference.heads;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * footnote 9 of
 * https://www.ark.cs.cmu.edu/SEMAFOR/das+schneider+chen+smith.tr10.pdf
 * "If the target is not a subtree in the parse, we consider the words that have
 *  parents outside the span, and apply three heuristic rules to select the
 *  head: 1) choose the first word if it is a verb; 2) choose the last word if
 *  the first word is an adjective; 3) if the target contains the word of, and
 *  the first word is a noun, we choose it. If none of these hold, choose the
 *  last word with an external parent to be the head."
 *
 * NOTE: This class has drifted and may no longer closely match SEMAFOR's style
 *
 * @author travis
 */
public class SemaforicHeadFinder implements HeadFinder {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(SemaforicHeadFinder.class);
  public static boolean DEBUG = false;

  private static SemaforicHeadFinder singleton;

  public static SemaforicHeadFinder getInstance() {
    if (singleton == null)
      singleton = new SemaforicHeadFinder();
    return singleton;
  }

  private static boolean isQuote(int i, Sentence sent) {
    String pos = sent.getPos(i);
    if ("''".equals(pos))
      return true;
    if ("``".equals(pos))
      return true;
    if ("\"".equals(pos))
      return true;
    return false;
  }

  private static boolean isPunc(int i, Sentence sent) {
    String pos = sent.getPos(i);
    if (",".equals(pos) || "--".equals(pos) || ":".equals(pos))
      return true;
    if ("$".equals(pos))
      return true;
    return false;
  }

  @Override
  public int head(Span s, Sentence sent) {
    if (s == Span.nullSpan)
      throw new IllegalArgumentException();
    if (s.width() == 0)
      throw new IllegalArgumentException();
    if (s.width() == 1)
      return s.start;

    // Not in their paper
    // Strip off quotations if they're present
    if (isQuote(s.start, sent) && isQuote(s.end-1, sent))
      return head(Span.getSpan(s.start + 1, s.end - 1), sent);

    // strip off leading and trailing punctuation
    if (isPunc(s.start, sent))
      return head(Span.getSpan(s.start + 1, s.end), sent);
    if (isPunc(s.end - 1, sent))
      return head(Span.getSpan(s.start, s.end - 1), sent);

    String lastPos = sent.getPos(s.end - 1);
    if ("WP".equals(lastPos) || "WDT".equals(lastPos))
      return head(Span.getSpan(s.start, s.end - 1), sent);

    // TODO this should really be a warning, this means that we've trimmed off
    // the NP to the right which should really contain the head.
    if ("'s".equalsIgnoreCase(sent.getWord(s.end - 1)))
      return head(Span.getSpan(s.start, s.end - 1), sent);

    // Removes ambiguity
    if (s.width() > 1
        && Arrays.asList("IN", "TO").contains(sent.getPos(s.start))) {
      return head(Span.getSpan(s.start+1, s.end), sent);
    }

    if(sent.getPos(s.start).startsWith("V"))
      return s.start;
    if (s.width() > 1
        && "TO".equals(sent.getPos(s.start))
        && sent.getPos(s.start + 1).startsWith("V")) {
      return s.start + 1;
    }

    int first_verb = -1;
    for (int i = s.start; i < s.end; i++) {
      if (sent.getPos(i).startsWith("V")) {
        first_verb = i;
        break;
      }
    }

    if (first_verb < 0) {
      // Take the right NP in (NP 's NP)
      for (int i = s.start + 1; i < s.end - 1; i++) {
        if ("POS".equals(sent.getPos(i)))
          return head(Span.getSpan(s.start, i), sent);
      }

      // Take the left NP in (NP [IN|TO] NP)
      for (int i = s.start + 1; i < s.end - 1; i++) {
        String p = sent.getPos(i).toUpperCase();
        if (("IN".equals(p) || "TO".equals(p) || "CC".equals(p))
            && sent.getPos(i-1).startsWith("N")) {
          return i-1;
        }
      }
    }

    // Scan for external dependency
    DependencyParse deps = sent.getBasicDeps();
    if (deps == null)
      deps = sent.getCollapsedDeps();
    if (deps != null) {
      for (int i = s.end - 1; i >= s.start; i--) {
        int p = deps.getHead(i);
        if (!s.includes(p)) {
          if (i > s.start
              && Arrays.asList("CC", "POS", "WDT", "TO", "IN")
              .contains(sent.getPos(i))) {
            return head(Span.getSpan(s.start, i), sent);
          } else {
            return i;
          }
        }
      }
    }

    // BELOW NOT IN THEIR PAPER:
    // collapsed dependencies might lead to "incest" (no parent outside this span)

    // choose the first verb
    if (first_verb >= 0)
      return first_verb;

    // choose the last word in a simple NP
    // DT* J* N+
    final int D = 0;
    final int J = 1;
    final int N = 2;
    int state = -1;
    for (int i = s.start; i < s.end; i++) {
      String p = sent.getPos(i).toUpperCase();
      if (state == -1) {
        if (p.endsWith("DT") || "PRP$".equals(p) || "CD".equals(p))
          state = D;
        else if (p.startsWith("J") || p.startsWith("R"))
          state = J;
        else if (p.startsWith("N"))
          state = N;
        else break;
      } else if (state == D) {
        if (p.startsWith("J") || p.startsWith("R"))
          state = J;
        else if (p.startsWith("N"))
          state = N;
        else break;
      } else if (state == J) {
        if (p.startsWith("J") || p.startsWith("R"))
          state = J;
        else if (p.startsWith("N"))
          state = N;
        else break;
      } else {
        assert state == N;
        if (p.startsWith("N"))
          state = N;
        else {
          state = -2;
          break;
        }
      }
    }
    if (state == N)
      return s.end - 1;
    if (state == J)
      return s.end - 1;

    // Give up and return the last token
    if (DEBUG && s.width() < 8) {
      LOG.warn("could not come up with reasonable head for: ["
          + sent.getId() + "]" + Arrays.toString(sent.getWordFor(s)));
    }
    return s.end - 1;
  }
}
