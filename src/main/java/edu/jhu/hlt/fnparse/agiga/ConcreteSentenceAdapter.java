package edu.jhu.hlt.fnparse.agiga;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.HasSentence;

public class ConcreteSentenceAdapter implements HasSentence {
  public static final Logger LOG = Logger.getLogger(ConcreteSentenceAdapter.class);

  private edu.jhu.hlt.concrete.Sentence cSent;
  private Sentence sentCache;

  public ConcreteSentenceAdapter(edu.jhu.hlt.concrete.Sentence adapted) {
    cSent = adapted;
  }

  private static Map<String, TokenTagging> indexByType(
      Collection<TokenTagging> tokenTaggings) {
    Map<String, TokenTagging> m = new HashMap<>();
    for (TokenTagging tt : tokenTaggings) {
      //LOG.info("[indexByName] " + tt.getTaggingType());
      TokenTagging old = m.put(tt.getTaggingType(), tt);
      if (old != null) {
        LOG.warn("duplicate TokenTaggings for " + tt.getTaggingType());
        m.put(tt.getTaggingType(), old);  // keep first one
      }
    }
    return m;
  }

  /**
   * Returns a sentence which has an id equivalent to the UUID of the Sentence
   * passed in at construction time.
   */
  @Override
  public Sentence getSentence() {
    if (sentCache == null) {
      Tokenization toks = cSent.getTokenization();
      int n = toks.getTokenList().getTokenList().size();
      Map<String, TokenTagging> tts = indexByType(toks.getTokenTaggingList());
      TokenTagging posTags = tts.get("POS");
      TokenTagging lemmaTags = tts.get("LEMMA");
      String[] tokens = new String[n];
      String[] pos = new String[n];
      String[] lemmas = new String[n];
      for (int i = 0; i < n; i++) {
        Token t = toks.getTokenList().getTokenList().get(i);
        assert i == t.getTokenIndex();
        tokens[i] = t.getText();
        pos[i] = posTags.getTaggedTokenList().get(i).getTag();
        lemmas[i] = lemmaTags.getTaggedTokenList().get(i).getTag();
      }
      int[] gov = null;
      String[] depType = null;
      String id = cSent.getUuid().getUuidString();
      sentCache = new Sentence("agiga2", id, tokens, pos, lemmas, gov, depType);
    }
    return sentCache;
  }

  public TextSpan getTextSpan(int tokenIndex) {
    return cSent.getTokenization().getTokenList().getTokenList().get(tokenIndex).getTextSpan();
  }

  public TextSpan getTextSpan(Span tokenIndices) {
    TextSpan start = getTextSpan(tokenIndices.start);
    TextSpan end = getTextSpan(tokenIndices.end - 1);
    TextSpan t = new TextSpan();
    t.setStart(start.getStart());
    t.setEnding(end.getEnding());
    return t;
  }

  public TokenRefSequence getTokenRefSequence(Span s) {
    TokenRefSequence trs = new TokenRefSequence();
    trs.setTokenizationId(cSent.getTokenization().getUuid());
    trs.setTextSpan(getTextSpan(s));
    for (int i = s.start; i < s.end; i++)
      trs.addToTokenIndexList(i);
    return trs;
  }
}