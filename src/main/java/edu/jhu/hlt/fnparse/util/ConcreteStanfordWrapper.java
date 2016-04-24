package edu.jhu.hlt.fnparse.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.log4j.Logger;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.Parse;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenList;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.TokenizationKind;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.stanford.AnnotateTokenizedConcrete;
import edu.jhu.hlt.concrete.stanford.PipelineLanguage;
import edu.jhu.hlt.concrete.stanford.StanfordPostNERCommunication;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory.AnalyticUUIDGenerator;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.StringLabeledDirectedGraph;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.util.Alphabet;

/**
 * Calls concrete-stanford and wraps the results in fnparse data structures.
 *
 * Only support English now.
 *
 * @author travis
 */
public class ConcreteStanfordWrapper {
  public static final Logger LOG = Logger.getLogger(ConcreteStanfordWrapper.class);

  private static CachingConcreteStanfordWrapper nonCachingSingleton;
  private static CachingConcreteStanfordWrapper cachingSingleton;

  public static final File cacheDir = new File("experiments/cache-parses");
  public static final File bdParseCacheFile = new File(cacheDir, "bdParseCache-all.bin");
  public static final File cParseCacheFile = new File(cacheDir, "cParseCache-all.bin");

  public static synchronized ConcreteStanfordWrapper getSingleton(boolean caching) {
    String k = "disallowConcreteStanford";
    if (ExperimentProperties.getInstance().getBoolean(k, false))
      throw new RuntimeException("on-the-fly parsing has been disallowed with " + k);
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

  public static void dumpSingletons() {
    LOG.info("[dumpSingletons] cachingSingleton=" + cachingSingleton
        + " nonCachingSingleton=" + nonCachingSingleton);
    cachingSingleton = null;
    nonCachingSingleton = null;
  }

  private UUID aUUID;
  private AnnotationMetadata metadata;
  private AnnotateTokenizedConcrete anno;
  private Timer parseTimer;

  public ConcreteStanfordWrapper() {
    AnalyticUUIDGeneratorFactory a = new AnalyticUUIDGeneratorFactory();
    AnalyticUUIDGenerator ag = a.create();
    aUUID = ag.next();
    metadata = new AnnotationMetadata();
    metadata.setTool("fnparse");
    metadata.setTimestamp(System.currentTimeMillis() / 1000);
    anno = null;
    parseTimer = new Timer("ConcreteStanfordAnnotator.parse", 5, false);
    parseTimer.showTimeHistInToString = true;
  }

  private AnnotateTokenizedConcrete getAnno() {
    if (anno == null)
      anno = new AnnotateTokenizedConcrete(PipelineLanguage.ENGLISH);
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
      c.setStart(i);
      c.setEnding(i + 1);
      cons.add(c);
      preterms.add(i);
    }
    Constituent root = new Constituent();
    root.setId(s.size());
    root.setTag("S");
    root.setChildList(preterms);
    root.setStart(0);
    root.setEnding(s.size());
    cons.add(root);
    p.setConstituentList(cons);
    p.setUuid(aUUID);
    p.setMetadata(metadata);
    return new ConstituencyParse(s.getId(), p);
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
      //getAnno().annotateWithStanfordNlp(communication);
      StanfordPostNERCommunication spc = getAnno().annotate(communication);
      communication = spc.getRoot();
      communication.setId(s.getId());
      parseTimer.stop();
      return communication;
    } catch (Throwable e) {   // Throwable includes AssertionError, which Exception doesn't
      LOG.warn("failed to parse " + s.getId());
      e.printStackTrace();
      parseTimer.stop();
      return null;
    }
  }

//  public DependencyParse getBasicDParse(Sentence s) {
//    Communication communication = parse(s);
//    if (communication == null)
//      return dummyDParse(s);
  public static DependencyParse getBasicDParse(Communication communication) {
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
    int n = tokenization.getTokenList().getTokenListSize();
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

  public static StringLabeledDirectedGraph getColDParse(
      Communication communication, Alphabet<String> edgeAlph) {
    Section section = communication.getSectionList().get(0);
    edu.jhu.hlt.concrete.Sentence sentence = section.getSentenceList().get(0);
    Tokenization tokenization = sentence.getTokenization();
    Optional<edu.jhu.hlt.concrete.DependencyParse> maybeDeps =
        tokenization.getDependencyParseList()
        .stream()
        .filter(dp -> dp.getMetadata().getTool().toLowerCase().contains("col"))
        .filter(dp -> !dp.getMetadata().getTool().toLowerCase().contains("cc"))
        .findFirst();
    if (!maybeDeps.isPresent())
      throw new RuntimeException("couldn't get col dep parse");
    int root = tokenization.getTokenList().getTokenListSize();
    return StringLabeledDirectedGraph.fromConcrete(maybeDeps.get(), edgeAlph, root);
  }

  public static StringLabeledDirectedGraph getColCCDParse(
      Communication communication, Alphabet<String> edgeAlph) {
    Section section = communication.getSectionList().get(0);
    edu.jhu.hlt.concrete.Sentence sentence = section.getSentenceList().get(0);
    Tokenization tokenization = sentence.getTokenization();
    Optional<edu.jhu.hlt.concrete.DependencyParse> maybeDeps =
        tokenization.getDependencyParseList()
        .stream()
        .filter(dp -> dp.getMetadata().getTool().toLowerCase().contains("col"))
        .filter(dp -> dp.getMetadata().getTool().toLowerCase().contains("cc"))
        .findFirst();
    if (!maybeDeps.isPresent())
      throw new RuntimeException("couldn't get col cc dep parse");
    int root = tokenization.getTokenList().getTokenListSize();
    return StringLabeledDirectedGraph.fromConcrete(maybeDeps.get(), edgeAlph, root);
  }

//  public ConstituencyParse getCParse(Sentence s) {
//   Communication communication = parse(s);
//   if (communication == null) {
//     LOG.warn("inserting dummy parse into " + s.getId());
//     return dummyCParse(s);
//   }
  public static ConstituencyParse getCParse(Communication communication) {
   Section section = communication.getSectionList().get(0);
   edu.jhu.hlt.concrete.Sentence sentence = section.getSentenceList().get(0);
   Tokenization tokenization = sentence.getTokenization();

   if (tokenization.getParseListSize() != 1)
     throw new RuntimeException("parseListSize=" + tokenization.getParseListSize());

   Parse p = tokenization.getParseList().get(0);

   // Check for a bad parse
   // Every span must lie within the sentence (sometimes sentence tokenization
   // doesn't match the parse tokenization).
   int n = tokenization.getTokenList().getTokenListSize();
   for (Constituent c : p.getConstituentList()) {
     if (c.getStart() < 0 || c.getStart() >= n
         || c.getEnding() > n || c.getEnding() <= c.getStart()) {
       throw new RuntimeException("stanford goofed");
     }
   }

//   return new ConstituencyParse(s.getId(), p, s.size());
   String id = communication.getId();
   return new ConstituencyParse(id, p, n);
  }

  public void addAllParses(Sentence s, Alphabet<String> edgeAlph, boolean overwrite) {
    Communication c = parse(s);
    if (c == null) {
      Log.warn("didn't add any parses to " + s.getId());
      return;
    }
    if (s.getBasicDeps(false) == null || overwrite) {
      DependencyParse deps = getBasicDParse(c);
      s.setBasicDeps(deps);
    }
    if (s.getStanfordParse(false) == null || overwrite) {
      ConstituencyParse cons = getCParse(c);
      s.setStanfordParse(cons);
    }
    if (edgeAlph != null) {
      if (s.getCollapsedDeps2(false) == null || overwrite) {
        StringLabeledDirectedGraph deps = getColDParse(c, edgeAlph);
        s.setCollapsedDeps2(deps);
      }
      if (s.getCollapsedCCDeps2(false) == null || overwrite) {
        StringLabeledDirectedGraph deps = getColCCDParse(c, edgeAlph);
        s.setCollapsedCCDeps2(deps);
      }
    }
  }


//  public Map<Span, String> parseSpans(Sentence s) {
//    Map<Span, String> constiuents = new HashMap<>();
//    edu.jhu.hlt.concrete.Parse parse = getCParse(s).getConcreteParse();
//    for (Constituent c : parse.getConstituentList()) {
//      Span mySpan = constituentToSpan(c);
//      String tag = c.getTag();
//      String oldTag = constiuents.put(mySpan, tag);
//      if (oldTag != null) {
//        if (tag.compareTo(oldTag) < 0) {
//          String temp = oldTag;
//          oldTag = tag;
//          tag = temp;
//        }
//        constiuents.put(mySpan, oldTag + "-" + tag);
//      }
//    }
//    return constiuents;
//  }

  public static Span constituentToSpan(Constituent c) {
    return Span.getSpan(c.getStart(), c.getEnding());
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
    sentence.setTextSpan(new TextSpan(0, docText.length()));

    Section section = new Section();
    section.setUuid(aUUID);
    section.setKind("estimateCardinalityOfTemplates");
    section.addToSentenceList(sentence);

    Communication communication = new Communication();
    communication.setUuid(aUUID);
    communication.setMetadata(metadata);
    communication.setId(s.getId());
    communication.setText(docText.toString());
    communication.addToSectionList(section);

    return communication;
  }

//  // Sanity check
//  public static void main(String[] args) {
//    ConcreteStanfordWrapper wrapper = getSingleton(false);
//    for (FNParse parse : DataUtil.iter2list(FileFrameInstanceProvider.debugFIP.getParsedSentences())) {
//      Sentence s = parse.getSentence();
//      System.out.println(s.getId() + "==============================================================");
//      System.out.println(s);
//      // Constituents
//      Map<Span, String> constituents = wrapper.parseSpans(s);
//      for (Map.Entry<Span, String> c : constituents.entrySet()) {
//        System.out.printf("%-6s %s\n",
//            c.getValue(),
//            Arrays.toString(s.getWordFor(c.getKey())));
//      }
//      // Dependencies
//      DependencyParse basicDeps = wrapper.getBasicDParse(s);
//      s.setBasicDeps(basicDeps);
//      System.out.println("collapsed deps:\n" + Describe.sentenceWithDeps(s, false));
//      System.out.println("basic deps:\n" + Describe.sentenceWithDeps(s, true));
//    }
//  }
}
