package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenList;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.TokenizationKind;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.stanford.AnnotateTokenizedConcrete;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class ConcreteStanfordWrapper {
  public static final Logger LOG = Logger.getLogger(ConcreteStanfordWrapper.class);

  private static ConcreteStanfordWrapper nonCachingSingleton, cachingSingleton;

  public static synchronized ConcreteStanfordWrapper getSingleton(boolean caching) {
    if (caching) {
      if (cachingSingleton == null)
        cachingSingleton = new ConcreteStanfordWrapper(true);
      return cachingSingleton;
    } else {
      if (nonCachingSingleton == null)
        nonCachingSingleton = new ConcreteStanfordWrapper(false);
      return nonCachingSingleton;
    }
  }

  private UUID aUUID;
  private AnnotationMetadata metadata;
  private AnnotateTokenizedConcrete anno;
  private Map<Sentence, Communication> cache = null;
  private Timer parseTimer;

  public ConcreteStanfordWrapper(boolean cache) {
    aUUID = new UUID();
    aUUID.setUuidString("some uuid");
    metadata = new AnnotationMetadata();
    metadata.setTool("fnparse");
    metadata.setTimestamp(System.currentTimeMillis() / 1000);
    anno = new AnnotateTokenizedConcrete();
    parseTimer = new Timer("ConcreteStanfordAnnotator.parse", 5, false);
    if (cache)
      this.cache = new HashMap<>();
  }

  public ConstituencyParse getCParse(Sentence s) {
    return new ConstituencyParse(parse(s, false));
  }

  /** Makes a parse where everything is a child of root */
  public edu.jhu.hlt.concrete.Parse dummyParse(Sentence s, boolean setBasicDeps) {
    edu.jhu.hlt.concrete.Parse p = new edu.jhu.hlt.concrete.Parse();
    List<Integer> preterms = new ArrayList<>();
    List<Constituent> cons = new ArrayList<>();
    for (int i = 0; i < s.size(); i++) {
      Constituent c = new Constituent();
      c.setId(i);
      c.setTag("X");
      c.setChildList(new ArrayList<>());
      TokenRefSequence trs = new TokenRefSequence();
      trs.setTokenizationId(aUUID); // TODO this isn't right, but I don't think I ever use it
      trs.setTokenIndexList(Arrays.asList(i));
      c.setTokenSequence(trs);
      cons.add(c);
      preterms.add(i);
    }
    Constituent root = new Constituent();
    root.setId(s.size());
    root.setTag("S");
    root.setChildList(preterms);
    TokenRefSequence trs = new TokenRefSequence();
    trs.setTokenizationId(aUUID); // TODO this isn't right, but I don't think I ever use it
    trs.setTokenIndexList(preterms);
    root.setTokenSequence(trs);
    cons.add(root);
    p.setConstituentList(cons);
    p.setUuid(aUUID);
    p.setMetadata(metadata);
    if (setBasicDeps) {
      int[] heads = new int[s.size()];
      String[] labels = new String[s.size()];
      Arrays.fill(heads, -1);
      Arrays.fill(labels, "DUMMY");
      s.setBasicDeps(new DependencyParse(heads, labels));
    }
    return p;
  }

  public synchronized edu.jhu.hlt.concrete.Parse parse(
      Sentence s,
      boolean storeBasicDeps) {
    Communication communication = null;
    boolean updateCache = false;
    if (cache != null) {
      communication = cache.get(s);
      if (communication == null)
        LOG.info("cache miss for " + s.getId());
      updateCache = communication == null;
    }
    if (communication == null) {
      parseTimer.start();
      communication = sentenceToConcrete(s);
      try {
        anno.annotateWithStanfordNlp(communication);
      } catch (Exception e) {
        LOG.warn("failed to parse " + s.getId());
        e.printStackTrace();
        return dummyParse(s, storeBasicDeps);
      }
      parseTimer.stop();
    }
    if (updateCache) {
      Communication old = cache.put(s, communication);
      assert old == null;
    }
    for (Section section : communication.getSectionList()) {
      for (edu.jhu.hlt.concrete.Sentence sentence : section.getSentenceList()) {
        Tokenization tokenization = sentence.getTokenization();
        if (storeBasicDeps) {
          Optional<edu.jhu.hlt.concrete.DependencyParse> deps =
              tokenization.getDependencyParseList()
              .stream()
              .filter(dp ->
              dp.getMetadata().getTool().contains("basic"))
              .findFirst();
          if (deps.isPresent()) {
            int n = s.size();
            int[] heads = new int[n];
            Arrays.fill(heads, DependencyParse.PUNC);
            String[] labels = new String[n];
            for (Dependency e : deps.get().getDependencyList()) {
              assert heads[e.getDep()] == DependencyParse.PUNC;
              heads[e.getDep()] = e.isSetGov()
                  ? e.getGov() : DependencyParse.ROOT;
              labels[e.getDep()] = e.getEdgeType();
            }
            s.setBasicDeps(new DependencyParse(heads, labels));
          } else {
            throw new RuntimeException("couldn't get basic dep parse");
          }
        }
        return tokenization.getParseList().get(0);
      }
    }
    throw new RuntimeException();
  }

  public Map<Span, String> parseSpans(Sentence s) {
    Map<Span, String> constiuents = new HashMap<>();
    edu.jhu.hlt.concrete.Parse parse = parse(s, false);
    for (Constituent c : parse.getConstituentList()) {
      Span mySpan = constituentToSpan(c);
      String tag = c.getTag();
      String oldTag = constiuents.put(mySpan, tag);
      if (oldTag != null) {
        if (tag.compareTo(oldTag) < 0) {
          String temp = oldTag;
          oldTag = tag;
          tag = temp;
        }
        constiuents.put(mySpan, oldTag + "-" + tag);
      }
    }
    return constiuents;
  }

  public static Span constituentToSpan(Constituent c) {
    int start = -1, end = -1;
    for (int x : c.getTokenSequence().getTokenIndexList()) {
      if (start < 0 || x < start)
        start = x;
      if (end < 0 || x > end)
        end = x;
    }
    return Span.getSpan(start, end + 1);
  }

  public static String normalizeToken(String token) {
    if (token.contains("(")) {
      if (token.length() == 1) {
        return "RLB";
      } else {
        if (!token.startsWith("("))
          LOG.warn("LRB that is not a left paren? " + token);
        // e.g. "(Hong"
        return token.substring(1, token.length());
      }
    } else if (token.contains(")")) {
      if (token.length() == 1) {
        return "RRB";
      } else {
        if (!token.endsWith(")"))
          LOG.warn("RRB that is not a right paren? " + token);
        // e.g. "Kong)"
        return token.substring(0, token.length() - 1);
      }
    } else {
      return token;
    }
  }

  public Communication sentenceToConcrete(Sentence s) {
    TokenList tokList = new TokenList();
    StringBuilder docText = new StringBuilder();
    for (int i = 0; i < s.size(); i++) {
      String w = normalizeToken(s.getWord(i));
      Token t = new Token();
      t.setTokenIndex(i);
      t.setText(w);
      TextSpan span = new TextSpan();
      span.setStart(docText.toString().length());
      span.setEnding(span.getStart() + w.length());
      t.setTextSpan(span);
      tokList.addToTokenList(t);
      if (i > 0) docText.append(" ");
      docText.append(w);
    }
    Tokenization tokenization = new Tokenization();
    tokenization.setUuid(aUUID);
    tokenization.setMetadata(metadata);
    tokenization.setKind(TokenizationKind.TOKEN_LIST);
    tokenization.setTokenList(tokList);
    TokenTagging pos = new TokenTagging();
    pos.setUuid(aUUID);
    pos.setMetadata(metadata);
    pos.setTaggingType("POS");
    for (int i = 0; i < s.size(); i++) {
      TaggedToken tt = new TaggedToken();
      tt.setTokenIndex(i);
      tt.setTag(s.getPos(i));
      pos.addToTaggedTokenList(tt);
    }
    tokenization.addToTokenTaggingList(pos);

    edu.jhu.hlt.concrete.Sentence sentence =
        new edu.jhu.hlt.concrete.Sentence();
    sentence.setUuid(aUUID);
    sentence.setTokenization(tokenization);

    Section section = new Section();
    section.setUuid(aUUID);
    section.setKind("main");
    section.addToSentenceList(sentence);

    Communication communication = new Communication();
    communication.setUuid(aUUID);
    communication.setMetadata(metadata);
    communication.setId(s.getId());
    communication.setText(docText.toString());
    communication.addToSectionList(section);

    return communication;
  }

  // Sanity check
  public static void main(String[] args) {
    ConcreteStanfordWrapper wrapper = new ConcreteStanfordWrapper(false);
    for (FNParse parse : DataUtil.iter2list(FileFrameInstanceProvider.debugFIP.getParsedSentences())) {
      Sentence s = parse.getSentence();
      System.out.println(s.getId() + "==============================================================");
      System.out.println(s);
      // Constituents
      Map<Span, String> constituents = wrapper.parseSpans(s);
      for (Map.Entry<Span, String> c : constituents.entrySet()) {
        System.out.printf("%-6s %s\n",
            c.getValue(),
            Arrays.toString(s.getWordFor(c.getKey())));
      }
      // Dependencies
      wrapper.parse(s, true);
      System.out.println("collapsed deps:\n" + Describe.sentenceWithDeps(s, false));
      System.out.println("basic deps:\n" + Describe.sentenceWithDeps(s, true));
    }
  }
}
