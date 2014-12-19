package edu.jhu.hlt.fnparse.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

  private static CachingConcreteStanfordWrapper nonCachingSingleton;
  private static CachingConcreteStanfordWrapper cachingSingleton;

  public static final File cacheDir = new File("experiments/cache-parses");
  public static final File bdParseCacheFile = new File(cacheDir, "bdParseCache-all.bin");
  public static final File cParseCacheFile = new File(cacheDir, "cParseCache-all.bin");

  public static synchronized ConcreteStanfordWrapper getSingleton(boolean caching) {
    if (caching) {
      if (cachingSingleton == null) {
        cachingSingleton = new CachingConcreteStanfordWrapper(bdParseCacheFile, cParseCacheFile, true);
        cachingSingleton.load();
      }
      return cachingSingleton;
    } else {
      if (nonCachingSingleton == null) {
        nonCachingSingleton = new CachingConcreteStanfordWrapper(bdParseCacheFile, cParseCacheFile, false);
        if (bdParseCacheFile.isFile() && cParseCacheFile.isFile()) {
          nonCachingSingleton.load();
        } else {
          LOG.warn("[load] not loading because one of the following doesn't exist:\n"
              + bdParseCacheFile.getPath() + "\n" + cParseCacheFile.getPath());
        }
      }
      return nonCachingSingleton;
    }
  }

  private UUID aUUID;
  private AnnotationMetadata metadata;
  private AnnotateTokenizedConcrete anno;
  private Timer parseTimer;

  public ConcreteStanfordWrapper() {
    aUUID = new UUID();
    aUUID.setUuidString("some uuid");
    metadata = new AnnotationMetadata();
    metadata.setTool("fnparse");
    metadata.setTimestamp(System.currentTimeMillis() / 1000);
    anno = null;
    parseTimer = new Timer("ConcreteStanfordAnnotator.parse", 5, false);
  }

  private AnnotateTokenizedConcrete getAnno() {
    if (anno == null)
      anno = new AnnotateTokenizedConcrete();
    return anno;
  }

  /** Makes a parse where everything is a child of root */
  public ConstituencyParse dummyCParse(Sentence s) {
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
    /*
    TokenRefSequence trs = new TokenRefSequence();
    trs.setTokenizationId(aUUID); // TODO this isn't right, but I don't think I ever use it
    trs.setTokenIndexList(preterms);
    root.setTokenSequence(trs);
    */
    cons.add(root);
    p.setConstituentList(cons);
    p.setUuid(aUUID);
    p.setMetadata(metadata);
    return new ConstituencyParse(p);
  }

  public DependencyParse dummyDParse(Sentence s) {
    int[] heads = new int[s.size()];
    String[] labels = new String[s.size()];
    Arrays.fill(heads, -1);
    Arrays.fill(labels, "DUMMY");
    return new DependencyParse(heads, labels);
  }

  private synchronized Communication parse(Sentence s) {
    parseTimer.start();
    Communication communication = sentenceToConcrete(s);
    try {
      getAnno().annotateWithStanfordNlp(communication);
      parseTimer.stop();
      return communication;
    } catch (Exception e) {
      LOG.warn("failed to parse " + s.getId());
      e.printStackTrace();
      parseTimer.stop();
      return null;
    }
  }

  public DependencyParse getBasicDParse(Sentence s) {
    Communication communication = parse(s);
    if (communication == null)
      return dummyDParse(s);
    Section section = communication.getSectionList().get(0);
    edu.jhu.hlt.concrete.Sentence sentence = section.getSentenceList().get(0);
    Tokenization tokenization = sentence.getTokenization();
    Optional<edu.jhu.hlt.concrete.DependencyParse> maybeDeps =
        tokenization.getDependencyParseList()
        .stream()
        .filter(dp -> dp.getMetadata().getTool().contains("basic"))
        .findFirst();
    if (!maybeDeps.isPresent())
      throw new RuntimeException("couldn't get basic dep parse");
    int n = s.size();
    int[] heads = new int[n];
    String[] labels = new String[n];
    Arrays.fill(heads, DependencyParse.ROOT);
    Arrays.fill(labels, "UNK");
    boolean[] set = new boolean[n];
    for (Dependency e : maybeDeps.get().getDependencyList()) {
      if (!set[e.getDep()]) {
        set[e.getDep()] = true;
      } else {
        LOG.warn("this token has more than one head!");
        continue;
      }
      heads[e.getDep()] = e.isSetGov()
          ? e.getGov() : DependencyParse.ROOT;
          labels[e.getDep()] = e.getEdgeType();
    }
    return new DependencyParse(heads, labels);
  }

  public ConstituencyParse getCParse(Sentence s) {
   Communication communication = parse(s);
   if (communication == null)
     return dummyCParse(s);
   Section section = communication.getSectionList().get(0);
   edu.jhu.hlt.concrete.Sentence sentence = section.getSentenceList().get(0);
   Tokenization tokenization = sentence.getTokenization();
   int n = tokenization.getTokenList().getTokenListSize();
   for (Constituent c : tokenization.getParseList().get(0).getConstituentList()) {
     if (!c.isSetTokenSequence())
       continue;
     for (int t : c.getTokenSequence().getTokenIndexList()) {
       if (t >= n) {
         for (Constituent cc : tokenization.getParseList().get(0).getConstituentList())
           LOG.warn(cc);
         LOG.warn("there are " + n + " tokens in the tokenization");
         //throw new RuntimeException("concrete-stanford messed up");
         LOG.warn("concrete-stanford messed up");
         return dummyCParse(s);
       }
     }
   }
   return new ConstituencyParse(tokenization.getParseList().get(0), s.size());
  }

  public Map<Span, String> parseSpans(Sentence s) {
    Map<Span, String> constiuents = new HashMap<>();
    edu.jhu.hlt.concrete.Parse parse = getCParse(s).getConcreteParse();
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
    ConcreteStanfordWrapper wrapper = getSingleton(false);
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
      DependencyParse basicDeps = wrapper.getBasicDParse(s);
      s.setBasicDeps(basicDeps);
      System.out.println("collapsed deps:\n" + Describe.sentenceWithDeps(s, false));
      System.out.println("basic deps:\n" + Describe.sentenceWithDeps(s, true));
    }
  }
}
