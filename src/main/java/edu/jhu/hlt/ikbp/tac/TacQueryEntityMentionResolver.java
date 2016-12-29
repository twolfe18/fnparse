package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.util.TextSpanToTokens;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.util.TokenizationIter;

/**
 * Given a {@link KbpQuery} which char offsets into a document
 * and a {@link Communication}, either find the {@link EntityMention}
 * which has been added or add it.
 * 
 * Note: This class looks for essentially an exact match. Often CoreNLP
 * will get a mention like "Vladimir Ladyzhenskiy of Russia" when the
 * query wants just "Vladimir Ladyzhenskiy". When there is not a very
 * close match, this this class will create a new {@link EntityMention}
 * for "Vladimir Ladyzhenskiy".
 *
 * @author travis
 */
public class TacQueryEntityMentionResolver {
  public static boolean DEBUG = false;
  
  private AnnotationMetadata metaForNewEntityMentions;
  
  public TacQueryEntityMentionResolver(String toolnameForNewEntityMentions) {
    this(new AnnotationMetadata()
        .setTool(toolnameForNewEntityMentions)
        .setTimestamp(System.currentTimeMillis() / 1000));
  }

  public TacQueryEntityMentionResolver(AnnotationMetadata metaForNewEntityMentions) {
    this.metaForNewEntityMentions = metaForNewEntityMentions;
  }
  
  /**
   * Searches over all {@link EntityMentionSet}s.
   * Doesn't mutate q.
   * @param tolerance is how many characters off is acceptable, 0 means exact match
   */
  public static EntityMention find(KbpQuery q, double tolerance) {
    return find(q.sourceComm, q.beg, q.end, tolerance);
  }

  public static EntityMention find(Communication c, int cstart, int cend, double tolerance) {
    if (c == null)
      throw new IllegalArgumentException("argument must have resolved Communication");
    Map<String, Tokenization> tm = buildTokMap(c);
    double minErr = Double.MAX_VALUE;
    EntityMention minEm = null;
    if (c.isSetEntityMentionSetList()) {
      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {
          String tid = em.getTokens().getTokenizationId().getUuidString();
          Tokenization t = tm.get(tid);
          int ft = firstToken(em.getTokens());
          int lt = lastToken(em.getTokens());
          int fc = firstChar(ft, t);
          int lc = lastChar(lt, t);
          
          // Be careful to avoid overflow!
//          double err = (cstart - fc) * (cstart - fc)
//              + (cend - lc) * (cend - lc);
          double estart = cstart - fc;
          double eend = (cend - lc);
          double err = estart*estart + eend*eend;
          assert err >= 0;
          assert err >= estart;
          assert err >= eend;
          
          if (DEBUG && err <= 3*tolerance + 1) {
            Log.info("em=" + em);
            Log.info("err=" + err);
            Log.info("tol=" + tolerance);
            System.out.println();
          }
          if (minEm == null || err < minErr) {
            minErr = err;
            minEm = em;
          }
        }
      }
    }
    // If the match is bad, return nothing
    if (minErr <= tolerance * tolerance * 2)
      return minEm;
    if (DEBUG) {
      Log.info("failed to find (tol=" + tolerance + ") EntityMention for: " + c.getId() + " @ " + new IntPair(cstart, cend));
      Log.info("best guess minErr="+ minErr + " minEm=" + minEm);
      System.out.println();
    }
    return null;
  }

  private static int firstChar(int token, Tokenization t) {
    return t.getTokenList().getTokenList().get(token).getTextSpan().getStart();
  }
  private static int lastChar(int token, Tokenization t) {
    return t.getTokenList().getTokenList().get(token).getTextSpan().getEnding()-1;
  }
  private static int firstToken(TokenRefSequence trs) {
    int min = Integer.MAX_VALUE;
    for (int i : trs.getTokenIndexList())
      if (i < min)
        min = i;
    return min;
  }
  private static int lastToken(TokenRefSequence trs) {
    int max = -1;
    for (int i : trs.getTokenIndexList())
      if (i > max)
        max = i;
    return max;
  }
  
  /** Doesn't mutate q */
  public EntityMention buildEntityMention(KbpQuery q) {
    EntityMention em = new EntityMention();
    em.setUuid(new UUID("NA"));
    TextSpanToTokens ts2tok = new TextSpanToTokens();
    TextSpan ts = new TextSpan()
        .setStart(q.beg)
        .setEnding(q.end+1);
    TokenRefSequence x = ts2tok.resolve(ts, q.sourceComm);
    em.setTokens(x);
    em.setText(q.name);
    if (DEBUG) {
      Log.info("ts=" + ts);
      Log.info("just created " + em);
      Log.info("em.text=" + em.getText());
      Log.info("comm.text[ts]=" + q.sourceComm.getText().substring(ts.getStart(), ts.getEnding()));
      Log.info("comm[trs]=" + resolveT(x, q.sourceComm));
      System.out.println();
    }
    return em;
  }
  
  private List<Token> resolveT(TokenRefSequence trs, Communication c) {
    Tokenization tok = null;
    for (Tokenization t : new TokenizationIter(c)) {
      if (t.getUuid().equals(trs.getTokenizationId())) {
        assert tok == null;
        tok = t;
      }
    }
    if (tok == null)
      return null;
    List<Token> l = new ArrayList<>();
    for (int i : trs.getTokenIndexList()) {
      l.add(tok.getTokenList().getTokenList().get(i));
    }
    return l;
  }

  /**
   * Doesn't mutate q's {@link Communication} unless addEmToCommIfMissing
   * Always sets q's {@link EntityMention}.
   * Returns true if didn't find an {@link EntityMention} in q's {@link Communication}.
   */
  public boolean resolve(KbpQuery q, boolean addEmToCommIfMissing) {
    if (q.entityMention != null) {
      Log.info("warning: are you un-intentionally over-writing an existing EntityMention");
      assert !addEmToCommIfMissing;
    }
    q.entityMention = find(q, 3);
    if (q.entityMention != null)
      return false;
    // Adding
    q.entityMention = buildEntityMention(q);
    assert q.entityMention != null;
    if (addEmToCommIfMissing) {
      EntityMentionSet ems = findOrCreateEms(metaForNewEntityMentions, q.sourceComm);
      ems.addToMentionList(q.entityMention);
    }
    return true;
  }
  
  private static EntityMentionSet findOrCreateEms(AnnotationMetadata meta, Communication c) {
    for (EntityMentionSet ems : c.getEntityMentionSetList()) {
      if (ems.getMetadata().equals(meta))
        return ems;
    }
    return new EntityMentionSet()
        .setMetadata(meta)
        .setMentionList(new ArrayList<>());
  }
  
  private static Map<String, Tokenization> buildTokMap(Communication c) {
    return AddNerTypeToEntityMentions.buildTokzIndex(c);
  }
}
