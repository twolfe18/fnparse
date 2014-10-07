package edu.jhu.hlt.fnparse.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.SectionSegmentation;
import edu.jhu.hlt.concrete.SentenceSegmentation;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenList;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.TokenizationKind;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.stanford.AnnotateTokenizedConcrete;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class ConcreteStanfordWrapper {
  private UUID aUUID;
  private AnnotationMetadata metadata;
  private AnnotateTokenizedConcrete anno;

  public ConcreteStanfordWrapper() {
    aUUID = new UUID();
    aUUID.setUuidString("some uuid");
    metadata = new AnnotationMetadata();
    metadata.setTool("fnparse");
    metadata.setTimestamp(System.currentTimeMillis() / 1000);
    anno = new AnnotateTokenizedConcrete();
  }

  public edu.jhu.hlt.concrete.Parse parse(Sentence s, boolean storeBasicDeps) {
    Communication communication = sentenceToConcrete(s);
    anno.annotateWithStanfordNlp(communication);
    SectionSegmentation sectionSeg =
        communication.getSectionSegmentationList().get(0);
    for (Section section : sectionSeg.getSectionList()) {
      SentenceSegmentation sentenceSeg =
          section.getSentenceSegmentationList().get(0);
      for (edu.jhu.hlt.concrete.Sentence sentence :
        sentenceSeg.getSentenceList()) {
        Tokenization tokenization =
            sentence.getTokenizationList().get(0);
        assert tokenization.getParseList().size() == 1;
        if (storeBasicDeps) {
          Optional<DependencyParse> deps =
              tokenization.getDependencyParseList()
              .stream()
              .filter(dp ->
              dp.getMetadata().getTool().contains("basic"))
              .findFirst();
          if (deps.isPresent()) {
            int n = s.size();
            int[] heads = new int[n];
            Arrays.fill(heads, -1);
            String[] labels = new String[n];
            for (Dependency e : deps.get().getDependencyList()) {
              assert heads[e.getDep()] == -1;
              heads[e.getDep()] = e.getGov();
              labels[e.getDep()] = e.getEdgeType();
            }
            s.setBasicDeps(heads, labels);
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
        assert token.startsWith("(");
        // e.g. "(Hong"
        return token.substring(1, token.length());
      }
    } else if (token.contains(")")) {
      if (token.length() == 1) {
        return "RRB";
      } else {
        assert token.endsWith(")");
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
    sentence.addToTokenizationList(tokenization);
    SentenceSegmentation sentenceSeg = new SentenceSegmentation();
    sentenceSeg.setUuid(aUUID);
    sentenceSeg.setMetadata(metadata);
    sentenceSeg.addToSentenceList(sentence);
    Section section = new Section();
    section.setUuid(aUUID);
    section.setKind("main");
    section.addToSentenceSegmentationList(sentenceSeg);
    SectionSegmentation sectionSeg = new SectionSegmentation();
    sectionSeg.setUuid(aUUID);
    sectionSeg.setMetadata(metadata);
    sectionSeg.addToSectionList(section);
    Communication communication = new Communication();
    communication.setUuid(aUUID);
    communication.setMetadata(metadata);
    communication.setId(s.getId());
    communication.setText(docText.toString());
    communication.addToSectionSegmentationList(sectionSeg);
    return communication;
  }

  // Sanity check
  public static void main(String[] args) {
    ConcreteStanfordWrapper wrapper = new ConcreteStanfordWrapper();
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
